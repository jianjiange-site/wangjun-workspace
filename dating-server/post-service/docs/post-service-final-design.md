# Post 服务最终技术方案

## 1. 文档说明

本文为 Post 独立服务最终版技术方案，依据需求澄清、初版方案和技术评审修订而成，可作为后续 SQL、gRPC proto、配置、Docker、CI/CD 和开发任务拆分依据。

本方案已确认 Redis 缓存 Key 统一增加业务前缀 `wangjun:`。

本方案的开发环境、对象存储、Redis、Nacos、RocketMQ、proto 发布和本地联调约定遵循《开发环境接入手册》。开发环境凭据只能放在本机 profile、环境变量或 Nacos 配置中，禁止提交到业务代码仓库。

## 2. 30 秒概览

Post 服务负责帖子、图片、点赞、评论和简单 Feed。技术栈为：

`Spring Boot 3.3.5 + PostgreSQL + Redis + MyBatis-Plus + Nacos + gRPC + MinIO`

RocketMQ 和第三方审核 SDK 仅预留，不作为 MVP 必须实现。

核心规则：

- Feed 不抽离，不做复杂推荐，只做简单内容列表。
- Feed 来源为热门、喜欢过的人、新帖，比例 `8:1:1`；固定每页 20 条时目标配额为热门 16 条、喜欢过的人 2 条、新帖 2 条；喜欢过的人 ID 从 user-service 获取。
- 只推荐异性作者，性别来自 User/Profile 服务。
- 点赞只支持点赞，不支持取消；同一用户对同一帖子仅在 Redis TTL 窗口内去重，TTL 过后可再次点赞并重新计数。
- 点赞请求链路先写 Redis，Redis 负责 TTL 去重和实时计数 delta；后台任务只回写 PostgreSQL `post.like_count`，点赞关系不落库。
- 评论支持两级楼中楼；创建和删除请求链路先写 Redis pending 事实和计数 delta，再由后台任务按顺序回写 PostgreSQL。
- 图片由 Post 服务生成 MinIO 预签名上传 URL，客户端直传；业务入库和接口出参只使用 `object_key`，不存完整 URL。

## 3. 服务定位和职责边界

Post 服务负责：

- 创建帖子、删除帖子、帖子详情、作者帖子列表。
- 图片上传凭证、图片元数据、图片绑定、TEMP 图片清理。
- 点赞 TTL 去重、点赞计数。
- 一级评论和楼中楼回复，评论缓存写入和数据库异步回写。
- 简单 Feed 召回、异性过滤、去重、混排、cursor 分页。
- 审核 SDK 适配器预留，MVP no-op 默认通过。

Post 服务不负责：

- 用户资料、性别、头像、昵称。
- 关注、匹配、私信、通知。
- 搜索、曝光记录、已看屏蔽、复杂推荐。
- 编辑帖子、取消点赞。
- 后台管理、管理员下架接口。
- RocketMQ 真实投递链路。

数据归属：

- Post 服务拥有 `post`、`post_image`、`comment`、`idempotent_request` 等数据；点赞关系只保存在 Redis TTL 去重 key 中，不落 PostgreSQL 关系表。
- User/Profile 服务拥有用户资料和性别。
- Post 禁止直接读 User/Profile 数据库，只能通过 gRPC 调用。

## 4. 总体架构

```text
App/Web
  -> HTTP Gateway
      -> Post gRPC Service
          -> PostgreSQL
          -> Redis
          -> MinIO
          -> User/Profile gRPC
          -> Audit SDK Adapter(no-op)
```

同步链路：

- 网关校验 token。
- 网关通过 gRPC metadata 传递 `user_id`、`role`、`request_id`。
- Post 服务执行业务权限、数据读写、缓存读写。

异步/任务链路：

- 点赞 Redis delta 定时回写 PostgreSQL。
- 评论 Redis pending 创建/删除和计数 delta 定时回写 PostgreSQL。
- TEMP 图片定时清理 MinIO。
- RocketMQ 事件点仅预留，MVP 不启用。

## 5. 技术选型

| 技术 | 用途 | MVP |
|---|---|---|
| Spring Boot 3.3.5 | 服务框架 | 必须 |
| PostgreSQL | 主数据存储 | 必须 |
| MyBatis-Plus | ORM / Mapper | 必须 |
| Redis | 性别缓存、Feed 缓存、点赞 delta、评论 pending/delta | 必须 |
| gRPC | 内部服务接口 | 必须 |
| Nacos | 注册发现、配置 | 必须 |
| MinIO | 图片对象存储 | 必须 |
| RocketMQ | 领域事件预留 | 非必须 |
| 审核 SDK | 第三方审核预留 | 非必须 |

## 6. 项目结构

```text
site.jianjiange.postservice
├── PostServiceApplication.java
├── grpc/
├── service/
├── manager/
├── mapper/
├── entity/
├── client/
│   ├── profile/
│   └── audit/
├── storage/
├── cache/
├── job/
├── mq/
├── converter/
├── enums/
├── constant/
├── config/
└── exception/
```

调用方向：

```text
grpc/job -> service -> manager -> mapper -> database
service -> client/storage/cache
```

禁止：

- gRPC 入口直接调用 mapper。
- mapper 反向调用 service。
- entity 直接作为接口响应。
- 数据库事务内调用 MinIO、User/Profile、审核 SDK、RocketMQ。
- 持久层 Mapper 写多表 JOIN；一张 Mapper 只负责一张表，跨表组装放在 service/manager 层。
- Post 服务直接读取其他服务数据库。

## 7. 数据模型设计

通用约定：

- 所有时间字段使用 PostgreSQL `TIMESTAMPTZ`。
- 数据库连接设置 `SET TIME ZONE 'UTC'`，全系统按 UTC 存取时间，代码中不写死东八区。
- 跨服务数据只通过 gRPC 获取，不跨库 JOIN，不直接读其他服务表。
- MyBatis-Plus Mapper 保持单表访问；复杂查询先分别查表，再在 service/manager 层组合。
- 各表 `id` 仅作为数据库技术主键，不具备业务含义，不在 gRPC 协议中直接暴露；对外业务标识统一使用明确命名的 `post_no`、`image_no`、`comment_no`。
- 帖子和评论关系字段统一使用业务标识 `post_no`、`comment_no`，不使用技术主键做跨表或楼层关系关联。
- 数据库字段统一 `NOT NULL`；无关联 ID 使用 `-1`，未发生时间使用 `1970-01-01T00:00:00Z`，空文本使用空字符串。

### post

用途：帖子主体。

核心字段：

- `id`
- `post_no`
- `author_id`
- `content`
- `image_count`
- `status`：`PUBLISHED`、`USER_DELETED`、`AUDIT_REJECTED`
- `like_count`
- `comment_count`
- `published_at`
- `deleted_at`
- `version`
- `created_at`
- `updated_at`

索引：

- `idx_author_status_created(author_id, status, created_at desc)`
- `idx_status_created(status, created_at desc)`
- `idx_hot_window(status, published_at desc, like_count, comment_count)`
- `uk_post_no(post_no)`

说明：

- `like_count` 是 PostgreSQL 基准值，展示和热门计算时需要叠加 Redis delta。
- `comment_count` 是 PostgreSQL 基准值，表示已回写的未删除评论总数；评论展示和热门计算均直接使用该值。
- Post 不冗余作者性别。

### post_image

用途：TEMP 图片、已绑定帖子图片、清理状态管理。

核心字段：

- `id`
- `image_no`
- `user_id`
- `post_no`：帖子业务号
- `bucket`
- `object_key`
- `content_type`
- `size_bytes`
- `etag`
- `width`
- `height`
- `sort_order`
- `status`：`TEMP`、`BOUND`、`CLEANING`、`CLEANED`、`DELETE_FAILED`
- `upload_expire_at`
- `bound_at`
- `retry_count`
- `created_at`
- `updated_at`

索引：

- `idx_user_status(user_id, status)`
- `idx_temp_expire(status, upload_expire_at)`
- `idx_post_image_post_sort(post_no, sort_order)`
- `uk_post_image_no(image_no)`

说明：

- 发帖只允许绑定当前用户自己的 `TEMP` 图片。
- 发帖绑定前必须通过 MinIO `statObject` 校验对象真实存在、大小和 content-type 合法。

### 点赞关系

点赞关系不落 PostgreSQL，不再建立 `post_like` 表。Post 服务只持久化帖子维度的 `post.like_count` 基准计数。

说明：

- 不支持取消点赞。
- 同一用户对同一帖子通过 Redis TTL key 去重，TTL 窗口内重复点赞返回成功但不增加计数。
- TTL 过后同一用户可以再次点赞，视为新的点赞计数增量。
- Feed 的“喜欢过的人”来源从 user-service 获取喜欢过的人 ID，不依赖 Post 服务点赞关系表。

### comment

用途：一级评论和二级楼中楼。

核心字段：

- `id`
- `comment_no`
- `post_no`
- `author_id`
- `root_comment_no`
- `parent_comment_no`
- `reply_to_user_id`
- `content`
- `level`
- `status`：`NORMAL`、`USER_DELETED`
- `created_at`
- `deleted_at`

规则：

- 一级评论：`level = 1`，`root_comment_no = comment_no`，`parent_comment_no = -1`。
- 二级回复：`root_comment_no` 固定指向所属一级评论业务号，`parent_comment_no` 指向直接被回复评论业务号。
- 二级回复：`level = 2`，挂到一级评论下。
- 允许回复二级回复，但展示仍为两级。
- 一级评论和二级回复均只查询 PostgreSQL 已落库评论，并按 `created_at ASC, comment_no ASC` 返回，保证刷新后的分页顺序稳定。
- 一级评论列表按前端传入的 `cursor_created_at` + `cursor_comment_no` 游标向后读取，每页固定 50 条，不内嵌二级回复预览。
- 一级评论结果返回二级回复总数和总页数，用于前端展示“查看回复/页号”。
- 展开二级回复使用 `page_no` 页号分页，每页固定 10 条，不使用游标。
- 一级评论删除不级联删除回复。
- 评论创建请求先生成全局唯一且大体有序的 `comment_no`，写入 Redis pending 后立即返回；回写任务落库时直接写入 root/parent 业务号关系。
- 如果回复的父评论仍在 Redis pending 中，允许创建回复，回写任务必须先写入父评论再写入子回复。

### idempotent_request

用途：发帖、评论等写操作幂等。

核心字段：

- `id`
- `user_id`
- `operation_type`
- `client_request_id`
- `request_hash`
- `biz_no`
- `response_snapshot`
- `created_at`
- `expire_at`

唯一索引：

- `uk_user_op_req(user_id, operation_type, client_request_id)`

## 8. 缓存设计

### Redis Key 命名规范

统一格式：

```text
wangjun:{service}:{module}:{biz}:{id}
```

Post 服务 Redis Key 必须统一使用 `wangjun:` 前缀，避免多服务共用 Redis 时发生 Key 污染。

开发环境连接共享 Redis，默认使用 db `0`。普通缓存、点赞 delta、点赞 TTL 去重 key、评论 pending 和评论 delta 必须设置 TTL。普通缓存遵循“先写库，再删缓存”，不做数据库和缓存双写。点赞和评论属于高频写特例，请求链路以 Redis 作为第一写入点，先完成 TTL 去重、pending 事实和实时计数，再由回写任务批量累加 PostgreSQL 计数或写入评论事实。点赞关系不落 PostgreSQL。

### 用户性别缓存

- Key：`wangjun:post:profile:gender:{userId}`
- TTL：1 小时 + 随机抖动
- 来源：User/Profile gRPC
- 失败策略：当前用户性别获取失败且缓存未命中时，Feed 返回空或可重试错误；候选作者性别获取失败时，该作者不进入 Feed。

### 热门候选缓存

- Key：`wangjun:post:feed:hot:gender:{gender}:v1`
- 重建周期：每 5 分钟重建一次，按作者性别分桶；请求时根据当前用户性别读取异性作者桶。
- 热门范围：最近 3 天 `PUBLISHED` 帖子。
- 每个作者性别桶最多保留 500 条候选，只缓存 `post_no + score` 等轻量候选信息，Feed 返回前仍回库批量查询帖子、图片和最新计数。
- 分数：

```text
score = (W_base + alpha * likeCount + beta * commentCount) / pow(hoursSincePublished + 2, 1.5)

W_base = 10.0
alpha = 1.0
beta = 3.0
likeCount = db_like_count + redis_like_delta
commentCount = db_comment_count
hoursSincePublished = 当前时间与 published_at 的小时差
```

说明：

- `W_base = 10.0` 用于冷启动扶持，避免新帖初始分为 0。
- `alpha = 1.0` 表示点赞权重。
- `beta = 3.0` 表示评论权重，评论交互成本更高，因此权重更高。
- 时间衰减指数为 `1.5`，约等于发布 24 小时后分数衰减到初始量级的 `1/25`。
- 重建时作者性别获取失败的帖子不进入任何性别桶；本轮重建失败或结果异常少时，继续使用上一轮成功缓存兜底。

### 新帖候选缓存

- Key：`wangjun:post:feed:new:gender:{gender}:v1`
- 写入策略：发帖成功后按作者性别即时写入对应性别桶，保留最近 3 天 `PUBLISHED` 新帖，过期后移除；定时校准任务用于兜底修正。
- 排序：按 `published_at DESC, id DESC`。
- 缓存内容：只缓存 `post_no + published_at` 等轻量候选信息，Feed 返回前仍回库批量查询帖子、图片和最新计数。
- 读取策略：请求时根据当前用户性别读取异性作者桶。

### Feed 翻页短期状态

- Feed 固定每页 20 条，不允许客户端动态指定 pageSize。
- 喜欢过的人短期去重 Key：`wangjun:post:feed:liked:seen:{userId}:{targetGender}`，TTL 30 分钟；用户主动刷新 Feed 时清空。
- 新帖时间游标 Key：`wangjun:post:feed:new:cursor:{userId}:{targetGender}`，TTL 30 分钟；连续翻页只取更旧的新帖，用户主动刷新 Feed 或 TTL 过期时从最新位置重新开始。
- 热门不做严格跨页去重，允许热门帖子在连续翻页中重复出现；但同一页内所有来源仍必须按 `post_no` 去重。
- 不做长期曝光记录，不保证刷新后不重复。

### 点赞计数 delta

- 去重 Key：`wangjun:post:like:users:{post_no}:{user_id}`，使用 `SET NX EX` 表达用户-帖子维度 TTL 去重。
- Key：`wangjun:post:like:delta:{post_no}`
- 回写临时 Key：`wangjun:post:like:flushing:{post_no}`
- 点赞成功后通过 Redis Lua 原子执行：`SET NX EX` TTL 去重和 `INCR` delta，作为前台可见点赞数的实时增量。
- TTL：7 天；去重 key 到期后同一用户可再次点赞，delta 正常情况下会被回写任务清理，TTL 只作为兜底保护。
- 展示计数：详情、作者列表和 Feed 热度计算均使用 `post.like_count + redis_delta`；Redis 读失败时退回 PostgreSQL 基准值。
- 定时任务扫描 `like:delta:*`，先将 delta 原子转移为 flushing key，再按 flushing delta 累加 `post.like_count`。
- Redis 不可用：点赞请求失败并提示稍后重试，不退回同步写 PostgreSQL，避免高峰流量压垮数据库。

### 评论 pending 和异步回写

- 幂等 Key：`wangjun:post:comment:idempotent:{user_id}:{client_request_id}`，保存请求哈希和首次生成的 `comment_no`。
- pending 数据 Key：`wangjun:post:comment:pending:data:{comment_no}`，Hash 保存 `id`、`comment_no`、`post_no`、`author_id`、`root_comment_no`、`parent_comment_no`、`reply_to_user_id`、`content`、`level`、`created_at`、`deleted_at`、`status`、`persisted`。
- 创建队列 Key：`wangjun:post:comment:pending:create`，建议使用 Redis Stream 或 List 保存待回写 `comment_no`。
- 创建集合 Key：`wangjun:post:comment:pending:create:set`，用于回写任务判断创建事实是否仍未落库。
- 删除队列 Key：`wangjun:post:comment:pending:delete`，保存待软删除 `comment_no`。
- 删除集合 Key：`wangjun:post:comment:pending:delete:set`，作为删除 tombstone，避免重复删除并阻止继续回复。
- 评论写入链路通过 Redis Lua 原子完成幂等检查、pending 数据写入、创建队列写入和创建集合写入。
- 评论删除链路先写 Redis 删除 pending/tombstone 和删除队列；如果评论尚未落库，则可直接标记 pending 数据为 `USER_DELETED`，回写时按最终状态落库或跳过展示。
- 列表查询只读取 PostgreSQL 已落库评论，不合并 Redis pending；新评论或删除事实需要等待回写任务完成后，用户刷新列表才能看到变化。
- 回复总数使用 PostgreSQL 统计值，一级评论和二级回复分页均使用数据库排序结果。
- pending 数据 TTL 建议 7 天，正常情况下回写成功后删除；TTL 只作为兜底保护，必须监控 pending 积压，避免未回写评论过期丢失。
- Redis 不可用：创建评论和删除评论请求失败并提示稍后重试，不退回同步写 PostgreSQL。

### Feed 页面缓存

- 可选。
- Key：`wangjun:post:feed:page:{userId}:{cursorHash}`
- TTL：1-3 分钟
- 不作为曝光记录，不保证刷新后不重复。

### 分布式锁

- 点赞回写锁：`wangjun:post:lock:like-flush`
- 评论回写锁：`wangjun:post:lock:comment-flush`
- TEMP 图片清理锁：`wangjun:post:lock:image-clean`
- 锁必须设置短 TTL，避免任务实例异常退出后永久占锁。

## 9. 异步和消息设计

MVP 不启用 RocketMQ producer，只预留事件类和 topic 命名：

- `PostCreatedEvent`
- `PostDeletedEvent`
- `PostLikedEvent`
- `CommentCreatedEvent`
- `CommentDeletedEvent`
- `PostAuditRequestedEvent`

后续启用原则：

- 事件在数据库事务提交后发送。
- 发送失败不影响主业务。
- 消费端按业务号幂等。
- 需要补充重试、死信、补偿策略后再正式启用。
- 开发环境 topic、producer group、consumer group 使用 `wangjun-dev-post-*` 前缀隔离。
- RocketMQ AK/SK 只能放在 Nacos、环境变量或本机 profile，禁止提交到仓库。

## 10. 文件和对象存储设计

上传流程：

1. 客户端请求 `CreateImageUploadUrl`。
2. Post 创建 `post_image` TEMP 记录。
3. Post 返回 `image_no`、`object_key`、MinIO 预签名上传 URL。
4. 客户端直传 MinIO。
5. 发帖时提交 `image_no` 列表。
6. Post 校验图片归属、TEMP 状态、MinIO 对象存在、大小、content-type。
7. 发帖事务内创建 post，并将图片置为 `BOUND`。
8. 详情和 Feed 只返回图片 `object_key`，不返回也不存储完整 URL。

Object Key：

```text
wangjun-tmp/post/{owner_id}/{yyyymm}/{uuid}.{ext}
wangjun-post/{owner_id}/{yyyymm}/{uuid}.{ext}
```

约定：

- 入库只存 `object_key`，不存完整 URL。
- 接口出参使用 `image_key` / `object_key`，由前端、网关或统一对象访问层按环境拼接访问地址。
- 临时上传使用 `wangjun-tmp/` 前缀；MVP 发帖绑定后可保留原 `object_key`，清理任务必须按数据库状态清理，不能只按 `wangjun-tmp/` 前缀批量删除。
- 如果后续要求绑定后从 `wangjun-tmp/post/` 搬迁到 `wangjun-post/`，需要采用事务提交后的 copy + DB 更新 + 失败重试补偿，不放在发帖数据库事务内。

清理流程：

- 超过 24 小时未绑定 TEMP 图片进入清理。
- 状态流转：`TEMP -> CLEANING -> CLEANED/DELETE_FAILED`
- MinIO 删除失败则保留记录、增加重试次数，下轮继续。

开发环境配置：

- MinIO endpoint 使用 `https://minio-api.jianjiange.site`。
- Bucket 使用个人隔离桶，例如 `wangjun-dating`。
- S3 path-style access 必须开启。
- access key / secret key 只能放在 Nacos、环境变量或本机 profile，禁止提交到仓库。

## 11. 接口设计

Post 服务只暴露 gRPC，客户端 HTTP 由网关转换。

gRPC Service 规划：

```text
PostService
  - CreatePost
  - DeletePost
  - GetPost
  - ListAuthorPosts
  - GetFeed

InteractionService
  - LikePost
  - CreateComment
  - DeleteComment
  - ListPostComments
  - ListCommentReplies

MediaService
  - CreateImageUploadUrl
  - GetImageKeys
```

共 3 个 gRPC Service，12 个 RPC。全部属于 MVP 范围。

RPC 职责：

- `CreatePost`：创建帖子，提交文本和当前用户自己的 TEMP `image_no` 列表。
- `DeletePost`：作者软删除自己的帖子。
- `GetPost`：查询帖子详情，不可见帖子不返回。
- `ListAuthorPosts`：按作者查询主页帖子列表。
- `GetFeed`：获取简单 Feed，包含热门、喜欢过的人、新帖三路召回，异性过滤，同页去重，`8:1:1` 混排和 cursor。
- `LikePost`：点赞帖子，不支持取消，重复点赞幂等成功。
- `CreateComment`：创建一级评论或楼中楼回复，请求链路先写 Redis pending，后台任务异步回写 PostgreSQL。
- `DeleteComment`：评论作者软删除自己的评论，请求链路先写 Redis tombstone，后台任务异步回写 PostgreSQL。
- `ListPostComments`：分页查询帖子一级评论列表，并返回每条一级评论下的回复总数和回复总页数。
- `ListCommentReplies`：分页查询某个一级评论下的楼中楼回复列表，用于展开楼中楼。
- `CreateImageUploadUrl`：为当前用户创建 TEMP 图片记录，并返回 MinIO 预签名上传 URL、`image_no`、`object_key`；客户端用它直传图片到 MinIO，Post 服务不承接图片文件流量。
- `GetImageKeys`：按帖子业务号或图片业务号查询已绑定图片的 `object_key`，并校验调用者对帖子可见；用于详情、Feed 或主页场景拿到图片 key。它不返回完整 URL，访问 URL 由前端、网关或统一对象访问层按环境拼接或签发。

接口约束：

- 写接口携带 `client_request_id`；点赞依赖 Redis TTL 去重保证幂等；评论创建依赖 Redis 幂等 key 和 pending 数据保证重复请求返回首次结果。
- `CreatePost` 只接 `image_no`，不允许直接提交任意 objectKey。
- `ListPostComments` 指定 `post_no`、`cursor_created_at` 和 `cursor_comment_no`，只返回一级评论游标页，一级评论每页固定 50 条；首次查询游标传空或哨兵值，后续请求使用上次结果返回的下一游标；不返回楼中楼预览，但返回该一级评论下回复总数和总页数，用于前端展示二级回复页号；查询结果只来自 PostgreSQL 已落库评论。
- `ListPostComments` 查询不存在或不可见帖子时返回 `POST_NOT_FOUND`。
- `ListCommentReplies` 必须指定一级评论 `root_comment_no` 和 `page_no`，只返回该一级评论下按统一排序规则分页的二级回复页。
- `ListCommentReplies` 使用页号分页，二级回复每页固定 10 条，并基于 `created_at ASC, comment_no ASC` 稳定排序；查询结果只来自 PostgreSQL 已落库回复。
- `GetFeed` 固定每页 20 条，客户端不传动态 pageSize；使用服务端生成、签名、防篡改 cursor。
- `GetImageKeys` 必须校验帖子可见性，不能任意 `image_no` 换 object key。
- 如果部署策略要求私有桶短期访问 URL，由网关或统一对象访问层基于 `object_key` 签发，Post 业务表和核心 gRPC 协议仍以 key 为准。
- Feed 固定 pageSize 为 20；其他列表接口如需分页上限按各自接口定义。

Feed cursor 内容：

- 三路来源最后排序位置
- expireAt
- signature

说明：

- cursor 不使用 `anchor_time` 锁定快照，连续翻页时允许新发布帖子进入后续页。
- cursor 不携带 pageSize；Feed pageSize 固定为 20。
- Gateway 负责认证用户身份，Post 服务负责校验 cursor 签名和 Feed 翻页状态。

## 12. 核心业务流程

### 发帖流程

```text
校验身份
-> 校验文本/图片数量
-> 幂等检查
-> 校验 image_no 归属和 TEMP
-> MinIO statObject 校验真实上传
-> 事务内创建 post、绑定图片、写幂等记录
-> 事务提交
-> 审核 adapter no-op 默认通过
-> 返回 post_no
```

### 点赞流程

```text
校验身份
-> 校验帖子存在且 PUBLISHED
-> Redis Lua 原子执行 SET NX EX TTL 去重、INCR like delta
-> TTL 窗口内重复点赞返回幂等成功，不增加 delta
-> TTL 过后再次点赞可重新进入 delta
-> 定时任务按 flushing delta 累加 PostgreSQL like_count
-> Redis 失败则返回可重试失败，保护数据库
-> 返回成功
```

### Feed 流程

```text
获取当前用户性别
-> 从 user-service 获取当前用户喜欢过的人 ID
-> 按当前用户性别确定目标作者性别
-> 从目标性别热门缓存召回热门帖子
-> 从 user-service 返回的喜欢过的人中召回每个作者最新帖子
-> 从目标性别新帖缓存按用户时间游标召回新帖
-> 候选放大
-> 回库批量查询帖子、图片和计数
-> 过滤异性、排除自己、排除不可见帖子
-> 去重，优先级：喜欢过的人 > 热门 > 新帖
-> 同页限制同一作者默认最多 2 条，候选不足时可放宽该体验限制
-> 按 8:1:1 混排，固定 20 条时目标为 16/2/2
-> 不足按 新帖 > 热门 > 喜欢过的人 补位
-> 写入喜欢过的人短期去重集合和新帖时间游标
-> 返回图片 object_key
-> 返回 Feed 和 cursor
```

### 评论流程

```text
校验身份
-> 校验帖子 PUBLISHED
-> 校验父评论/root 评论合法，父评论可来自 PostgreSQL 或 Redis pending
-> 生成 comment_no 和服务端 created_at
-> Redis Lua 原子执行幂等检查、写 pending data、写创建队列和创建集合
-> Redis 写入成功即返回评论
-> 回写任务按 created_at、comment_no 和父子依赖顺序写入 PostgreSQL
-> 回写成功后清理 pending data、创建队列和创建集合
-> 返回评论
```

### 删除评论流程

```text
校验身份
-> 校验评论存在且作者为当前用户，评论可来自 PostgreSQL 或 Redis pending
-> Redis Lua 原子写删除 tombstone、写 pending data 和删除队列
-> Redis 写入成功即返回成功
-> 回写任务软删除 PostgreSQL 已落库评论，或在创建 pending 未落库时按最终删除状态处理
-> 回写成功后清理 pending data、删除队列和 tombstone
```

## 13. 状态机设计

帖子：

```text
PUBLISHED -> USER_DELETED
PUBLISHED -> AUDIT_REJECTED
```

- 初始状态：`PUBLISHED`
- MVP 审核 no-op，不会实际产生 `AUDIT_REJECTED`
- `USER_DELETED`、`AUDIT_REJECTED` 除管理员外全部不可见
- 管理员仅预留角色，不实现后台接口

评论：

```text
NORMAL -> USER_DELETED
```

图片：

```text
TEMP -> BOUND
TEMP -> CLEANING -> CLEANED
TEMP -> CLEANING -> DELETE_FAILED
DELETE_FAILED -> CLEANING
```

## 14. 事务设计

事务内执行：

- 创建帖子、绑定图片、写幂等记录。
- 删除帖子状态更新。
- 点赞回写任务按 Redis delta 累加 `post.like_count`。
- 评论回写任务创建/软删除 comment，并按实际成功写入结果累加或扣减 `post.comment_count`。

事务外执行：

- MinIO `statObject`、删除对象、生成上传预签名 URL。
- User/Profile gRPC 调用。
- 审核 SDK 调用。
- RocketMQ 事件发送。
- 点赞请求链路 Redis TTL 去重、delta `INCR` 和缓存刷新。
- 点赞回写任务的 Redis delta/flushing key 转移。
- 评论请求链路 Redis pending、幂等 key、创建/删除队列写入。
- 评论回写任务读取 Redis pending 队列并在成功落库后确认清理。

原则：不让远程调用拖长数据库事务。

## 15. 幂等设计

点赞：

- 请求链路通过 Redis `like:users:{post_no}:{user_id}` TTL key 实现快速去重。
- TTL 窗口内重复请求返回成功，不重复增加 Redis delta。
- TTL 过后同一用户再次点赞会重新计数。

发帖：

- 使用 `client_request_id`。
- `user_id + operation_type + client_request_id` 唯一。
- 请求 hash 不一致返回 `IDEMPOTENT_CONFLICT`。
- 重复请求返回首次结果。

评论：

- 请求链路使用 Redis 幂等 Key：`wangjun:post:comment:idempotent:{userId}:{clientRequestId}`。
- 幂等 value 保存请求 hash、`comment_no` 和响应快照；请求 hash 不一致返回 `IDEMPOTENT_CONFLICT`。
- 重复请求返回首次 Redis 响应结果；回写任务落库时按 `comment_no` 唯一约束兜底防重复。

图片：

- 只能绑定当前用户自己的 TEMP 图片。
- BOUND 图片不可再次绑定。
- objectKey 不由客户端自由指定。

## 16. 权限和安全设计

身份来源：

- 网关校验 token。
- Post 服务信任内网 gRPC metadata。
- metadata 包含 `user_id`、`role`、`request_id`。

安全要求：

- 服务间调用建议使用内部 token 或 mTLS。
- MinIO 使用共享开发环境 endpoint 和个人隔离 bucket。
- 上传预签名 URL 短有效期。
- 业务接口只返回 `object_key`，不返回完整 URL；如果需要访问 URL，由网关或统一对象访问层根据 key 生成。
- 限制图片大小、content-type、扩展名。
- 图片 objectKey 不使用用户原始文件名。
- 发帖、点赞、评论按用户限流。
- 不可见帖子不能查看、点赞、评论、获取图片 object key。

## 17. 异常码设计

核心错误码：

- `POST_NOT_FOUND`
- `POST_NOT_VISIBLE`
- `POST_NOT_PUBLISHED`
- `POST_DELETE_FORBIDDEN`
- `POST_CONTENT_EMPTY`
- `POST_IMAGE_TOO_MANY`
- `IMAGE_NOT_FOUND`
- `IMAGE_NOT_OWNED`
- `IMAGE_NOT_UPLOADED`
- `IMAGE_STATUS_INVALID`
- `IMAGE_CONTENT_TYPE_INVALID`
- `COMMENT_NOT_FOUND`
- `COMMENT_PARENT_INVALID`
- `COMMENT_STATUS_INVALID`
- `IDEMPOTENT_CONFLICT`
- `PROFILE_SERVICE_UNAVAILABLE`
- `MINIO_OPERATION_FAILED`
- `RATE_LIMITED`

## 18. 可靠性设计

点赞计数可靠性：

- 请求链路以 Redis 为第一写入点，PostgreSQL 只通过后台任务回写帖子点赞计数。
- Redis delta 做实时展示计数，也作为回写任务扫描入口。
- 回写任务使用 owner-safe 分布式锁，释放锁时必须匹配获取锁时写入的 owner token。
- 回写采用原子转移：delta 转移到 flushing key，再按 flushing delta 写 DB，成功删除 flushing key，失败保留重试。
- 点赞关系不落 PostgreSQL；TTL 窗口内去重由 Redis 保证，TTL 过后再次点赞允许再次计数。
- Redis 写入失败时点赞请求失败并提示重试，不绕过 Redis 直接写 PostgreSQL。

评论回写可靠性：

- 请求链路以 Redis pending 为第一写入点，PostgreSQL 只通过后台任务回写评论事实和评论计数。
- 回写任务使用 owner-safe 分布式锁，释放锁时必须匹配获取锁时写入的 owner token；写入时保留服务端生成的 `created_at` 和 `comment_no`，并按父子依赖确保一级评论先于其二级回复落库。
- 回写任务从 pending create/delete 队列读取事实，写 DB 成功后删除 pending 数据和队列成员，失败保留 pending 等待下次重试。
- 创建评论按 `comment_no` 唯一约束保证重复回写幂等；删除评论按当前状态判断，重复删除不重复扣减计数。
- 列表读链路只读取 PostgreSQL 已落库数据，未回写评论需要等待回写任务完成后刷新可见。
- Redis pending 数据丢失会导致未回写评论无法从 PostgreSQL 恢复，必须依赖 Redis 持久化、积压监控和告警保障。

TEMP 图片清理可靠性：

- 清理任务状态机驱动。
- MinIO 删除失败不删除 DB 记录。
- `DELETE_FAILED` 可重试。

Feed 可靠性：

- 当前用户性别获取失败且缓存未命中时，Feed 返回空或可重试错误，避免破坏“只推荐异性”规则。
- 候选作者性别获取失败时跳过对应作者，不使整个 Feed 失败。
- 候选放大，避免异性过滤和同作者限制后结果过少。
- Redis 热门或新帖缓存不可用时，允许限量回退 PostgreSQL 查询；热门兜底查最近 3 天 `PUBLISHED` 帖子并计算热度，新帖兜底查最近 3 天目标性别作者的新帖，候选数量必须受限。

多实例任务：

- 点赞回写、评论回写和图片清理必须加分布式锁。
- 任务重复执行必须幂等。

兜底方案：

- 总体原则：读链路可以降级，普通写链路必须保证数据真实落库后才返回成功；点赞和评论是高频写特例，必须保证 Redis 成功接收去重和 pending 事实后才返回成功。任何兜底都不能绕过权限校验、可见性校验和异性过滤规则。
- PostgreSQL 不可用：发帖、删帖等普通写操作直接失败并返回可重试错误；点赞和评论请求仍可在 Redis 可用时先成功接收，待 PostgreSQL 恢复后由回写任务累加计数或写入评论；Feed、详情、作者帖子列表等读接口可返回明确失败，不承诺离线可读。
- Redis 不可用：点赞、创建评论、删除评论请求直接失败并提示稍后重试，不退回同步写 PostgreSQL；点赞计数展示可退回 PostgreSQL `like_count` 基准值，评论数本身读取 PostgreSQL `comment_count`；热门和新帖候选缓存不可用时直接查 PostgreSQL 限量兜底，并使用基准计数计算热度；性别缓存不可用时直接调用 User/Profile。
- User/Profile 不可用：当前用户性别如果 Redis 缓存命中，则使用缓存继续生成 Feed；如果当前用户性别无缓存，则返回空 Feed 并提示稍后重试，避免破坏“只推荐异性”规则；候选作者性别获取失败时跳过该作者，结果不足时允许少于固定 pageSize 返回。
- MinIO 不可用：申请上传 URL 失败时直接返回错误；发帖绑定图片时 `statObject` 失败则发帖失败，不把未确认上传成功的图片绑定到帖子；TEMP 图片清理失败保持 `DELETE_FAILED` 状态并等待下次重试。
- Nacos 不可用：已启动实例继续使用本地已加载配置和已有服务发现缓存；新实例启动失败时不自动降级到硬编码地址；本地开发可临时在 profile 中指定直连地址，但不能提交到仓库。
- RocketMQ 不可用：MVP 本身不依赖 RocketMQ；后续启用事件发送后，MQ 失败不影响主业务提交，事件发送失败记录日志和指标，必要时通过数据库事实表补发。
- 审核 SDK 不可用：MVP 使用 no-op 默认通过；后续接入真实审核后，SDK 调用失败时保持帖子当前可见性策略不变，并记录待审核补偿任务，不能在数据库事务内阻塞发帖。
- Feed 候选不足：三路召回、异性过滤、去重、补位后仍不足固定 pageSize 时，返回实际数量，不额外放宽性别、状态、作者本人排除等硬规则；同页同作者最多 2 条属于体验规则，候选不足时可最后放宽。
- 点赞回写失败：保留 Redis delta 或 flushing key，后续任务继续重试；如果 Redis 数据丢失，未回写的点赞增量无法从 PostgreSQL 恢复，需要监控和告警保障。
- 评论回写失败：保留 Redis pending 数据和队列成员，后续任务继续重试；如果 Redis 数据丢失，未回写评论内容无法从 PostgreSQL 恢复，需要 Redis 持久化、积压监控和告警保障。

## 19. 可观测性设计

日志字段：

- `request_id`
- `user_id`
- `post_no`
- `comment_no`
- `image_no`
- `operation`
- `error_code`
- `latency_ms`

核心指标：

- 发帖成功率和失败率。
- 点赞 QPS、重复点赞数。
- Redis delta 积压量。
- 点赞回写成功/失败次数。
- 评论创建/删除 QPS。
- 评论 pending 积压量。
- 评论回写成功/失败次数。
- Feed P95/P99 延迟。
- User/Profile 批量查询耗时。
- MinIO stat/upload-url/object-key 处理失败率。
- TEMP 图片清理失败数。
- PostgreSQL 慢查询和连接池使用率。
- Feed 兜底返回次数和空 Feed 次数。
- Redis 降级读写次数。
- User/Profile 降级和性别缓存命中次数。
- 点赞计数重建次数。

告警：

- Feed 延迟超阈值。
- 点赞回写连续失败。
- 评论回写连续失败或 pending 积压接近 TTL。
- Redis 连接失败。
- MinIO 失败率升高。
- TEMP 图片积压过多。
- PostgreSQL 连接池耗尽。
- Feed 空结果比例异常升高。
- 兜底策略触发次数异常升高。

## 20. 本地开发和部署

开发环境模式：

- 本机 IDE 或本机 Docker 中运行 Post 服务。
- PostgreSQL、Redis、Nacos、MinIO、RocketMQ 使用团队共享开发基建。
- 开发环境凭据放在本机 `application-dev.yml`、环境变量或 Nacos，禁止提交到 git。
- 启动 profile 使用 `dev`。

共享基建连接约定：

- PostgreSQL：`38.76.188.242:5433`，建议库名 `wangjun-dating-dev`。
- Redis：`38.76.188.242:6380`，默认 db `0`，Key 使用 `wangjun:` 前缀。
- Nacos：`38.76.188.242:8848`，namespace 使用 `wangjun-dev`。
- MinIO endpoint：`https://minio-api.jianjiange.site`，bucket 使用 `wangjun-dating`，并开启 `path-style-access`。
- RocketMQ：NameServer `38.76.188.242:9876`，MVP 仅预留；后续 topic/group 使用 `wangjun-dev-post-*` 前缀。

关键配置：

- `spring.application.name = wangjun-post-service`
- PostgreSQL JDBC URL：`jdbc:postgresql://38.76.188.242:5433/wangjun-dating-dev?stringtype=unspecified`
- Hikari 初始化 SQL：`SET TIME ZONE 'UTC'`
- Redis database：`0`
- Nacos namespace：`wangjun-dev`
- Nacos Data ID：`wangjun-post-service-dev.yaml`
- MinIO endpoint、bucket、accessKey、secretKey
- User/Profile gRPC 服务名
- 上传 URL 过期时间
- TEMP 图片保留时间
- 点赞和评论回写周期
- Feed 固定 pageSize 和热门/新帖候选缓存参数

对象存储配置示例字段：

```yaml
dating:
  object-storage:
    provider: s3
    endpoint: https://minio-api.jianjiange.site
    region: us-east-1
    path-style-access: true
    bucket: wangjun-dating
```

部署建议：

- Post 服务无状态多实例。
- 定时任务使用分布式锁。
- 数据库迁移使用 Flyway 或 Liquibase。
- proto 不拷贝进业务源码树，统一打包发布到 Nexus。
- Java proto 坐标建议：`com.dating.wangjun.proto:wangjun-post-proto:0.1.0`。
- proto 版本必须显式锁定，禁止使用 `LATEST` / `RELEASE`。
- 网关和 Post 之间走内网 gRPC。
- 本机 Docker 联调时加入 `wangjun-dating-app` 网络，通过 Nacos 服务发现互相调用。
- 日志输出到 stdout，便于 Loki/Grafana 或 Docker 日志采集。

## 21. 开发任务拆分

1. 初始化 Spring Boot 3.3.5 Post 服务工程。
2. 接入 Nacos、PostgreSQL、Redis、MyBatis-Plus。
3. 定义 gRPC proto、metadata、错误码。
4. 建立表结构和 entity/mapper。
5. 实现帖子创建、删除、详情、作者帖子列表。
6. 实现 MinIO 上传 URL、object key 出参、statObject 校验。
7. 实现图片 TEMP、BOUND、清理状态机。
8. 实现点赞 TTL 去重、重复点赞幂等。
9. 实现 Redis 点赞 delta 和定时回写。
10. 实现点赞计数修复策略。
11. 实现评论 Redis pending、楼中楼、删除评论、数据库评论分页列表、楼中楼回复列表和评论回写任务。
12. 实现 User/Profile gRPC client 和性别缓存。
13. 实现 Feed 三路召回、异性过滤、去重、混排、cursor。
14. 预留审核 SDK adapter。
15. 预留 RocketMQ 事件定义。
16. 实现限流、异常处理、日志、指标。
17. 配置开发环境 Nacos namespace、PostgreSQL 库、Redis db、MinIO bucket 隔离。
18. 实现 Redis、User/Profile、MinIO、Feed 的兜底策略和指标。
19. 打包并发布 `wangjun-post-proto` 到 Nexus。
20. 编写单元测试、集成测试、gRPC 联调用例。

## 22. 阶段交接摘要

最终方案已固化：Post 服务是独立 gRPC 微服务，负责帖子、图片、点赞、评论和简单 Feed；对外规划 3 个 gRPC Service、12 个 RPC，已包含评论列表和楼中楼回复列表查询。使用 PostgreSQL 作为主存储，Redis 承担性别缓存、热门缓存、点赞计数缓冲和评论 pending/delta，MinIO 负责对象存储。方案已按开发手册修订：开发环境连接共享基建，PostgreSQL 使用独立库 `wangjun-dating-dev`，Redis 使用 db `0` 和 `wangjun:` 前缀，Nacos 使用 `wangjun-dev` namespace，MinIO 使用 `wangjun-dating` bucket，proto 发布到 Nexus 并使用 `com.dating.wangjun.proto:wangjun-post-proto` 坐标。评审中的关键风险已修复：补充了 MinIO 对象真实上传校验、点赞计数补偿与原子回写、Feed cursor 签名和大小控制、热门分时间衰减公式、评论缓存优先写入与回写、图片 object key 权限校验以及 Redis、User/Profile、MinIO、Nacos、RocketMQ、审核 SDK 的兜底方案。下一阶段可进入落地资产生成，重点产出 PostgreSQL DDL、gRPC proto、配置文件、Docker Compose、接口文档和开发任务清单。
