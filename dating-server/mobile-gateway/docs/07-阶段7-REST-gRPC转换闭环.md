# 阶段 7：REST/gRPC 转换闭环

## 1. 阶段目标

完成代表性公网 REST 到内部 gRPC 的转换闭环：以 `GET /api/v1/users/me` 为第一条 adapter，网关从 `AuthContext` 读取 `user_id`、`account_id`、`device_id`、`trace_id`，转换为 user-service gRPC 请求，设置 deadline 和 metadata，处理 gRPC 超时 / 异常并返回统一业务码。

## 2. 阶段类型

中耦合纵向切片。

## 3. 输入依据

- 最终方案第 9.2 章请求转发链路。
- 最终方案第 16.4 章 REST/gRPC 转换接口。
- 最终方案第 21、22、23 章安全、业务码和可靠性。

## 4. 输出结果

- `GatewayProxyController` 代表性接口。
- `UserGrpcAdapter.getCurrentUser`。
- REST DTO / VO 与 proto request / response 转换。
- gRPC metadata 注入 `trace_id`、`user_id`、`account_id`、`device_id`。
- gRPC deadline、超时 `10701`、异常 `10702`、转换失败 `10700`。

## 5. 前置依赖

- 阶段 3：JWT 验签与退出黑名单闭环。

## 6. 耦合度说明

- 耦合度等级：中耦合。
- 耦合度总分：6。
- 排序理由：依赖认证上下文和一个下游 gRPC 契约，但可通过 user-service mock 独立验收。

## 7. 数据库低耦合说明

本阶段不新增 `mobile-gateway` 表，不访问 user-service 数据库。用户资料归属 user-service，网关只通过 gRPC 获取。

## 8. 本阶段任务清单

| 任务编号 | 任务名称 | 是否 MVP 必做 | 耦合度 | 主要文件 | 验收方式 |
| --- | --- | --- | --- | --- | --- |
| 7.1 | 代表性 user query proto / client | 是 | 中 | proto、`UserGrpcClient` | gRPC mock 测试 |
| 7.2 | REST/gRPC adapter 转换 | 是 | 中 | `UserGrpcAdapter`、converter | 单元测试 |
| 7.3 | 受保护 REST 转发接口 | 是 | 中 | `GatewayProxyController` | HTTP API 测试 |
| 7.4 | gRPC 错误映射和 deadline | 是 | 中 | adapter、异常处理 | 异常测试 |

## 9. 涉及文件总览

```text
src/main/proto/user_query.proto
src/main/java/com/dating/gateway/controller/GatewayProxyController.java
src/main/java/com/dating/gateway/service/GatewayProxyService.java
src/main/java/com/dating/gateway/grpc/adapter/UserGrpcAdapter.java
src/main/java/com/dating/gateway/client/UserGrpcClient.java
src/main/java/com/dating/gateway/converter/RestGrpcConverter.java
src/main/java/com/dating/gateway/vo/CurrentUserVO.java
src/test/java/com/dating/gateway/grpc/UserGrpcAdapterTest.java
src/test/java/com/dating/gateway/proxy/UserProxyIntegrationTest.java
```

## 10. 详细任务拆分

### 任务 7.1：代表性 user query proto / client

- 任务编号：7.1
- 任务名称：代表性 user query proto / client
- 所属阶段：阶段 7
- 阶段类型：中耦合纵向切片
- 业务闭环：网关能通过 gRPC 查询当前用户资料。
- 涉及文件：`user_query.proto`、`UserGrpcClient.java`
- 作用：提供 REST/gRPC 转换的第一个真实下游契约。
- 前置条件：阶段 1、阶段 3。
- 被阻塞于：阶段 1、阶段 3。
- 是否阻塞后续：是。
- 耦合度说明：依赖 user-service gRPC 契约。
- 数据库变更：无。
- API / gRPC / MQ 契约：`UserQueryService.GetCurrentUser`，请求至少包含 `user_id`。
- 实现要点：gRPC client 通过 Nacos 发现；必须设置 deadline。
- 事务 / 幂等 / 一致性：只读调用，无事务。
- 异常和权限：下游超时映射 `10701`，异常映射 `10702`。
- 验收标准：mock user-service 返回资料时 client 可解析；超时映射正确。
- 验证方式：gRPC mock 测试。
- 是否 MVP 必做：是。
- 建议执行方式：AI 生成初稿。

### 任务 7.2：REST/gRPC adapter 转换

- 任务编号：7.2
- 任务名称：REST/gRPC adapter 转换
- 所属阶段：阶段 7
- 阶段类型：中耦合纵向切片
- 业务闭环：REST 请求可转换为 proto request，proto response 可转换为 REST VO。
- 涉及文件：`UserGrpcAdapter.java`、`RestGrpcConverter.java`、`CurrentUserVO.java`
- 作用：统一 adapter 模式，供后续业务接口复制。
- 前置条件：任务 7.1。
- 被阻塞于：任务 7.1。
- 是否阻塞后续：是。
- 耦合度说明：本服务内部转换 + 一个 gRPC 契约。
- 数据库变更：无。
- API / gRPC / MQ 契约：REST VO 不暴露数据库内部 `id`。
- 实现要点：metadata 注入 `trace_id`、`user_id`、`account_id`、`device_id`；转换失败返回 `10700`。
- 事务 / 幂等 / 一致性：只读，无事务。
- 异常和权限：AuthContext 缺失返回 `10100`。
- 验收标准：metadata 完整；VO 只包含允许对外暴露字段；转换异常返回 `10700`。
- 验证方式：Adapter 单元测试。
- 是否 MVP 必做：是。
- 建议执行方式：AI 辅助修改。

### 任务 7.3：受保护 REST 转发接口

- 任务编号：7.3
- 任务名称：受保护 REST 转发接口
- 所属阶段：阶段 7
- 阶段类型：中耦合纵向切片
- 业务闭环：客户端携带 access token 调用 `/api/v1/users/me`，网关返回 user-service 当前用户资料。
- 涉及文件：`GatewayProxyController.java`、`GatewayProxyService.java`
- 作用：验证公网 REST 到内部 gRPC 主链路。
- 前置条件：任务 7.2、阶段 3。
- 被阻塞于：任务 7.2、阶段 3。
- 是否阻塞后续：是。
- 耦合度说明：依赖鉴权和 user-service mock。
- 数据库变更：无。
- API / gRPC / MQ 契约：`GET /api/v1/users/me` 需要鉴权。
- 实现要点：Controller 不直接调 Mapper；只通过 Service / Adapter 调 gRPC。
- 事务 / 幂等 / 一致性：只读，无事务。
- 异常和权限：未登录 `10100`，权限不足 `10101`。
- 验收标准：合法 token 返回当前用户 VO；无 token 返回 `10100`；gRPC metadata 包含认证上下文。
- 验证方式：HTTP API + gRPC mock 集成测试。
- 是否 MVP 必做：是。
- 建议执行方式：AI 辅助修改。

### 任务 7.4：gRPC 错误映射和 deadline

- 任务编号：7.4
- 任务名称：gRPC 错误映射和 deadline
- 所属阶段：阶段 7
- 阶段类型：中耦合纵向切片
- 业务闭环：下游慢或异常时，网关返回明确业务码而不是挂死请求。
- 涉及文件：adapter、异常处理、测试。
- 作用：落实下游调用可靠性。
- 前置条件：任务 7.3。
- 被阻塞于：任务 7.3。
- 是否阻塞后续：是。
- 耦合度说明：依赖 gRPC 异常路径。
- 数据库变更：无。
- API / gRPC / MQ 契约：gRPC deadline 配置化。
- 实现要点：每个下游方法有明确超时；状态码映射为统一 `Result.code`。
- 事务 / 幂等 / 一致性：只读，无事务。
- 异常和权限：DEADLINE_EXCEEDED -> `10701`；其他 gRPC 异常 -> `10702`。
- 验收标准：mock 超时返回 `10701`；mock 抛异常返回 `10702`；接口响应包含 traceId。
- 验证方式：异常注入集成测试。
- 是否 MVP 必做：是。
- 建议执行方式：AI 辅助测试。

## 11. 执行顺序

1. 定义代表性 user query proto 和 mock。
2. 实现 UserGrpcAdapter 和转换器。
3. 实现受保护 REST 接口。
4. 补齐 gRPC deadline 和错误映射测试。

## 12. 验收标准

- `GET /api/v1/users/me` 无 token 返回 `10100`。
- 合法 token 请求会调用 user-service gRPC mock。
- gRPC metadata 包含 `trace_id`、`user_id`、`account_id`、`device_id`。
- gRPC 超时返回 `10701`。
- gRPC 异常返回 `10702`。
- REST VO 不暴露数据库内部 `id`。

## 13. 默认假设 / 待确认事项

- `GET /api/v1/users/me` 作为代表性 adapter；后续其他 REST/gRPC 接口按同一模式复制。
- user-service 查询 proto 字段可在联调时扩展，但 `user_id` 透传不变。

## 14. 交付物

- 代表性 REST/gRPC 转换接口。
- UserGrpcAdapter 模式。
- metadata 注入和 gRPC 错误映射测试。

## 15. 下一阶段交接摘要

阶段 7 完成后，网关已经具备稳定的认证上下文透传和 REST/gRPC 转换模式。阶段 8 可以基于多个 adapter 做 BFF 只读聚合和字段裁剪。
