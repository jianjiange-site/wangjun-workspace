# 阶段 4：refresh token 轮换闭环

## 1. 阶段目标

完成 `/api/v1/auth/refresh` 闭环：客户端提交 refresh token 后，网关解析 jti 和随机 secret，校验过期时间、数据库 HMAC hash 和状态，通过条件更新把旧 token 从 ACTIVE 置为 ROTATED，再插入新的 refresh token，并返回新的 access token 和 refresh token。

## 2. 阶段类型

中耦合纵向切片。

## 3. 输入依据

- 最终方案第 12.4 章 `gateway_refresh_token`。
- 最终方案第 16.2 章认证接口。
- 最终方案第 17.5 章 refresh token 刷新。
- 最终方案第 19、20 章事务和幂等。
- 最终方案第 22、23 章业务码和可靠性。

## 4. 输出结果

- `/api/v1/auth/refresh` 接口。
- refresh token jti、随机 secret、过期时间、HMAC hash、状态校验。
- ACTIVE -> ROTATED 条件更新。
- 旧 token 重复使用返回 `10506`。
- 新 access token / refresh token 签发和持久化。
- Redis refresh 缓存更新。

## 5. 前置依赖

- 阶段 2：设备快速登录最小闭环。

## 6. 耦合度说明

- 耦合度等级：中耦合。
- 耦合度总分：5。
- 排序理由：依赖 refresh token 表、JwtService 和 Redis refresh 缓存，但不依赖外部服务。

## 7. 数据库低耦合说明

本阶段复用阶段 2 创建的 `gateway_refresh_token` 表，不新增表。核心数据库变更是条件更新：

```sql
UPDATE gateway_refresh_token
SET status = 2, updated_at = now()
WHERE token_jti = :old_jti
  AND status = 1
  AND deleted = 0;
```

影响行数为 1 才允许插入新的 refresh token。

## 8. 本阶段任务清单

| 任务编号 | 任务名称 | 是否 MVP 必做 | 耦合度 | 主要文件 | 验收方式 |
| --- | --- | --- | --- | --- | --- |
| 4.1 | refresh token 校验和错误码映射 | 是 | 中 | `JwtService`、`RefreshTokenManager` | 单元测试 |
| 4.2 | 条件轮换事务 | 是 | 中 | `RefreshTokenManager` | Service 集成测试 |
| 4.3 | `/api/v1/auth/refresh` API | 是 | 中 | `AuthController`、`AuthService` | HTTP API 测试 |
| 4.4 | 并发刷新和重复使用检测 | 是 | 中 | 测试 | 并发测试 |

## 9. 涉及文件总览

```text
src/main/java/com/dating/gateway/controller/AuthController.java
src/main/java/com/dating/gateway/service/AuthService.java
src/main/java/com/dating/gateway/service/JwtService.java
src/main/java/com/dating/gateway/manager/RefreshTokenManager.java
src/main/java/com/dating/gateway/dto/RefreshTokenRequest.java
src/main/java/com/dating/gateway/vo/LoginTokenVO.java
src/test/java/com/dating/gateway/auth/RefreshTokenIntegrationTest.java
src/test/java/com/dating/gateway/auth/RefreshTokenConcurrencyTest.java
```

## 10. 详细任务拆分

### 任务 4.1：refresh token 校验和错误码映射

- 任务编号：4.1
- 任务名称：refresh token 校验和错误码映射
- 所属阶段：阶段 4
- 阶段类型：中耦合纵向切片
- 业务闭环：只有合法且未过期的 refresh token 可以进入轮换流程。
- 涉及文件：`JwtService.java`、`RefreshTokenManager.java`
- 作用：解析 refresh token 的 jti 和随机 secret，校验过期时间、状态和数据库 hash。
- 前置条件：阶段 2。
- 被阻塞于：阶段 2。
- 是否阻塞后续：是。
- 耦合度说明：依赖 token 签发服务和 PostgreSQL。
- 数据库变更：读取 `gateway_refresh_token`。
- API / gRPC / MQ 契约：`POST /api/v1/auth/refresh` 请求体携带 refresh token。
- 实现要点：数据库只保存 `HMAC-SHA256(refresh_token_hmac_secret, refresh_token_random_secret)`；禁止保存明文。
- 事务 / 幂等 / 一致性：校验和轮换在同一业务流程内完成。
- 异常和权限：无效 `10503`，过期 `10504`，已撤销 `10505`。
- 验收标准：篡改 token、过期 token、hash 不匹配、已撤销 token 均返回正确业务码。
- 验证方式：单元测试、数据库断言。
- 是否 MVP 必做：是。
- 建议执行方式：AI 辅助修改。

### 任务 4.2：条件轮换事务

- 任务编号：4.2
- 任务名称：条件轮换事务
- 所属阶段：阶段 4
- 阶段类型：中耦合纵向切片
- 业务闭环：旧 refresh token 只能成功轮换一次。
- 涉及文件：`RefreshTokenManager.java`、Mapper。
- 作用：实现 ACTIVE -> ROTATED 条件更新和新 token 插入。
- 前置条件：任务 4.1。
- 被阻塞于：任务 4.1。
- 是否阻塞后续：是。
- 耦合度说明：依赖本地数据库事务。
- 数据库变更：更新旧行，插入新行。
- API / gRPC / MQ 契约：无。
- 实现要点：影响行数非 1 返回 `10506`；新 token 使用新的 `refresh_token_id` 和 `token_jti`。
- 事务 / 幂等 / 一致性：条件更新和新 token 插入必须在同一事务内。
- 异常和权限：并发冲突返回 `10506`。
- 验收标准：同一个旧 refresh token 并发请求只有一个成功，其余返回 `10506`。
- 验证方式：Service 并发集成测试。
- 是否 MVP 必做：是。
- 建议执行方式：人工审查事务边界。

### 任务 4.3：`/api/v1/auth/refresh` API

- 任务编号：4.3
- 任务名称：`/api/v1/auth/refresh` API
- 所属阶段：阶段 4
- 阶段类型：中耦合纵向切片
- 业务闭环：客户端可用 refresh token 获取一组新 token。
- 涉及文件：`AuthController.java`、`AuthService.java`、DTO / VO。
- 作用：提供 refresh token 刷新入口。
- 前置条件：任务 4.1、4.2。
- 被阻塞于：任务 4.1、4.2。
- 是否阻塞后续：否。
- 耦合度说明：依赖阶段 2 的 JwtService 和 refresh 表。
- 数据库变更：更新旧 refresh token，插入新 refresh token。
- API / gRPC / MQ 契约：`POST /api/v1/auth/refresh` 无需 access token 鉴权。
- 实现要点：刷新成功后写 Redis refresh 缓存，TTL 为新 refresh token 剩余时间。
- 事务 / 幂等 / 一致性：事务提交后再写 Redis。
- 异常和权限：请求体格式错误返回 `10002`，参数错误返回 `10001`。
- 验收标准：合法 refresh token 返回新的 access token 和 refresh token；旧 refresh token 再次使用返回 `10506`。
- 验证方式：HTTP API 集成测试。
- 是否 MVP 必做：是。
- 建议执行方式：AI 辅助修改。

### 任务 4.4：并发刷新和重复使用检测

- 任务编号：4.4
- 任务名称：并发刷新和重复使用检测
- 所属阶段：阶段 4
- 阶段类型：中耦合纵向切片
- 业务闭环：客户端重放旧 refresh token 时能被识别。
- 涉及文件：并发测试。
- 作用：验证 token 轮换安全性。
- 前置条件：任务 4.3。
- 被阻塞于：任务 4.3。
- 是否阻塞后续：否。
- 耦合度说明：依赖数据库事务隔离。
- 数据库变更：无新增。
- API / gRPC / MQ 契约：无。
- 实现要点：并发压测同一个 refresh token，统计成功数。
- 事务 / 幂等 / 一致性：只有一个请求能完成 ACTIVE -> ROTATED。
- 异常和权限：重复使用返回 `10506`。
- 验收标准：20 个并发刷新同一 token，只允许 1 个返回成功，其余返回 `10506`。
- 验证方式：并发集成测试。
- 是否 MVP 必做：是。
- 建议执行方式：AI 辅助测试。

## 11. 执行顺序

1. 实现 refresh token 校验。
2. 实现条件轮换事务。
3. 实现 refresh API。
4. 补齐并发和重复使用测试。

## 12. 验收标准

- `POST /api/v1/auth/refresh` 合法请求返回新 token。
- 旧 refresh token 状态变为 ROTATED。
- 新 refresh token 已插入数据库，且数据库只保存 HMAC hash。
- 旧 refresh token 再次使用返回 `10506`。
- 过期 token 返回 `10504`，撤销 token 返回 `10505`，无效 token 返回 `10503`。
- 并发刷新只有一个成功。

## 13. 默认假设 / 待确认事项

- refresh token 使用 SecureRandom 不透明随机串，格式为 `rt_<jti>.<random_secret>`，入库只保存 random secret 的 HMAC hash。
- refresh token 缓存以 PostgreSQL 为准，Redis 仅加速，不作为最终数据源。

## 14. 交付物

- refresh token 刷新 API。
- 条件轮换事务。
- 重复使用检测。
- 并发刷新测试。

## 15. 下一阶段交接摘要

阶段 4 完成后，认证会话生命周期已闭合。阶段 5 和阶段 6 可以专注于不同凭据的登录 / 注册，不需要重复实现 refresh token 轮换逻辑。
