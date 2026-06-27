# 阶段 6：Google 登录闭环

## 1. 阶段目标

完成 Google 登录 / 注册闭环：客户端提交 `google_id_token` 后，网关解析 header.kid，读取或刷新 Google JWKS，校验签名、issuer、audience、exp，提取 Google subject，使用 `GOOGLE + subject_hash` 查找或创建账号，调用 user-service 初始化并签发 token。

## 2. 阶段类型

中耦合纵向切片。

## 3. 输入依据

- 最终方案第 10 章 Google token 校验选型。
- 最终方案第 13 章 Google JWKS 缓存。
- 最终方案第 16.2 章认证接口。
- 最终方案第 17.2 章 Google 登录 / 注册。
- 最终方案第 21、22、23 章安全、业务码和可靠性。

## 4. 输出结果

- `/api/v1/auth/login/google` 接口。
- `GoogleTokenClient` 和 JWKS 本地缓存。
- kid miss 时刷新 JWKS。
- audience / issuer / exp 校验。
- GOOGLE 类型账号创建 / 登录。
- Google token 异常映射 `10300-10302`。

## 5. 前置依赖

- 阶段 2：设备快速登录最小闭环。
- 阶段 3：JWT 验签与退出黑名单闭环。

## 6. 耦合度说明

- 耦合度等级：中耦合。
- 耦合度总分：7。
- 排序理由：复用本服务账号和 token 能力，但新增 Google JWKS / ID token 第三方校验，因此晚于设备和手机号登录。

## 7. 数据库低耦合说明

本阶段不新增表。Google subject 原文不落库，使用 `GOOGLE + subject_hash` 写入 `gateway_account.account_type` 和 `account_key_hash`。

## 8. 本阶段任务清单

| 任务编号 | 任务名称 | 是否 MVP 必做 | 耦合度 | 主要文件 | 验收方式 |
| --- | --- | --- | --- | --- | --- |
| 6.1 | Google JWKS 拉取和缓存 | 是 | 中 | `GoogleTokenClient` | 单元 / mock 测试 |
| 6.2 | Google ID token 校验 | 是 | 中 | `GoogleLoginService` | 单元测试 |
| 6.3 | Google 账号登录 / 注册 | 是 | 中 | `AuthService`、`GatewayAccountManager` | HTTP API 测试 |
| 6.4 | Google 异常映射和脱敏 | 是 | 中 | 异常处理、日志 | 异常测试 |

## 9. 涉及文件总览

```text
src/main/java/com/dating/gateway/controller/AuthController.java
src/main/java/com/dating/gateway/service/GoogleLoginService.java
src/main/java/com/dating/gateway/service/AuthService.java
src/main/java/com/dating/gateway/client/GoogleTokenClient.java
src/main/java/com/dating/gateway/config/GoogleLoginConfig.java
src/main/java/com/dating/gateway/dto/GoogleLoginRequest.java
src/main/java/com/dating/gateway/support/HmacHasher.java
src/test/java/com/dating/gateway/auth/GoogleLoginServiceTest.java
src/test/java/com/dating/gateway/auth/GoogleLoginIntegrationTest.java
```

## 10. 详细任务拆分

### 任务 6.1：Google JWKS 拉取和缓存

- 任务编号：6.1
- 任务名称：Google JWKS 拉取和缓存
- 所属阶段：阶段 6
- 阶段类型：中耦合纵向切片
- 业务闭环：网关可根据 kid 获取 Google 公钥。
- 涉及文件：`GoogleTokenClient.java`、`GoogleLoginConfig.java`
- 作用：支持 Google ID token 签名校验。
- 前置条件：阶段 1。
- 被阻塞于：阶段 1。
- 是否阻塞后续：是。
- 耦合度说明：依赖第三方 HTTP，但可用 mock server 验收。
- 数据库变更：无。
- API / gRPC / MQ 契约：Google JWKS HTTP 调用，使用 JDK HttpClient，禁止 Feign / RestTemplate。
- 实现要点：本地内存缓存优先；kid miss 时刷新；可选 Redis 缓存 key `{redis_prefix}:google:jwks:{kid}`。
- 事务 / 幂等 / 一致性：无。
- 异常和权限：JWKS 刷新失败 fail closed。
- 验收标准：缓存命中不发 HTTP；kid miss 触发刷新；刷新失败返回可映射异常。
- 验证方式：mock server 单元测试。
- 是否 MVP 必做：是。
- 建议执行方式：AI 辅助修改。

### 任务 6.2：Google ID token 校验

- 任务编号：6.2
- 任务名称：Google ID token 校验
- 所属阶段：阶段 6
- 阶段类型：中耦合纵向切片
- 业务闭环：只有合法 Google ID token 能进入账号登录。
- 涉及文件：`GoogleLoginService.java`
- 作用：校验签名、issuer、audience、exp，并提取 subject。
- 前置条件：任务 6.1。
- 被阻塞于：任务 6.1。
- 是否阻塞后续：是。
- 耦合度说明：依赖 Google 公钥和本地配置 `client-id`。
- 数据库变更：无。
- API / gRPC / MQ 契约：客户端传入 `google_id_token`。
- 实现要点：audience 必须匹配 `gateway.google.client-id`；过期 token 返回 `10301`；audience 不匹配返回 `10302`。
- 事务 / 幂等 / 一致性：无。
- 异常和权限：无效 token 返回 `10300`。
- 验收标准：有效 token 提取 subject；过期、签名错误、issuer 错误、audience 错误均被拒绝。
- 验证方式：单元测试。
- 是否 MVP 必做：是。
- 建议执行方式：人工审查安全校验。

### 任务 6.3：Google 账号登录 / 注册

- 任务编号：6.3
- 任务名称：Google 账号登录 / 注册
- 所属阶段：阶段 6
- 阶段类型：中耦合纵向切片
- 业务闭环：Google token 合法后创建或查询 GOOGLE 账号并签发 token。
- 涉及文件：`AuthController.java`、`AuthService.java`、`GatewayAccountManager.java`
- 作用：完成 Google 登录入口。
- 前置条件：任务 6.2、阶段 2。
- 被阻塞于：任务 6.2、阶段 2。
- 是否阻塞后续：否。
- 耦合度说明：复用账号表、user-service 初始化和 token 签发。
- 数据库变更：写入或读取 `gateway_account`，`account_type=GOOGLE`。
- API / gRPC / MQ 契约：`POST /api/v1/auth/login/google`。
- 实现要点：Google subject HMAC 后落库；不与手机号或设备号账号合并。
- 事务 / 幂等 / 一致性：唯一键冲突后重查；本地事务不调用 gRPC。
- 异常和权限：账号禁用 `10103`，初始化失败 `10703`。
- 验收标准：首次 Google 登录创建账号并返回 token；再次同 subject 登录复用同一 `user_id`。
- 验证方式：HTTP + mock JWKS + gRPC mock 集成测试。
- 是否 MVP 必做：是。
- 建议执行方式：AI 辅助修改。

### 任务 6.4：Google 异常映射和脱敏

- 任务编号：6.4
- 任务名称：Google 异常映射和脱敏
- 所属阶段：阶段 6
- 阶段类型：中耦合纵向切片
- 业务闭环：Google 登录失败时返回明确业务码且不泄漏 token。
- 涉及文件：异常处理、日志配置、测试。
- 作用：保护 Google token 和 subject 原文。
- 前置条件：任务 6.3。
- 被阻塞于：任务 6.3。
- 是否阻塞后续：否。
- 耦合度说明：本服务内部异常路径。
- 数据库变更：无。
- API / gRPC / MQ 契约：无。
- 实现要点：日志禁止打印 `google_id_token`、Google subject 原文和 JWKS 私密信息。
- 事务 / 幂等 / 一致性：无。
- 异常和权限：JWKS 刷新失败返回 `10300`，不创建账号。
- 验收标准：异常日志不包含 Google token；JWKS 不可用时不写 `gateway_account`。
- 验证方式：异常注入测试、日志断言。
- 是否 MVP 必做：是。
- 建议执行方式：AI 辅助测试。

## 11. 执行顺序

1. 实现 JWKS 拉取和缓存。
2. 实现 Google ID token 校验。
3. 实现 Google 登录 API。
4. 补齐异常映射和日志脱敏测试。

## 12. 验收标准

- `POST /api/v1/auth/login/google` 使用合法 token 返回 access token 和 refresh token。
- 首次 Google 登录创建 `account_type=GOOGLE` 的账号。
- 再次同 Google subject 登录复用同一 `user_id`。
- 过期 token 返回 `10301`。
- audience 不匹配返回 `10302`。
- 无效 token 或 JWKS 刷新失败返回 `10300`，且不创建账号。
- 日志不出现 Google token 和 subject 原文。

## 13. 默认假设 / 待确认事项

- 只实现 Google 登录，不实现 Apple / 微信。
- Google JWKS 先以内存缓存为主，Redis 缓存为可选优化。

## 14. 交付物

- Google 登录接口。
- Google JWKS 客户端和缓存。
- GOOGLE 账号登录 / 注册测试。
- Google 异常映射和脱敏测试。

## 15. 下一阶段交接摘要

阶段 6 完成后，三条登录路径都已可用。阶段 7 可以在稳定认证上下文基础上实现公网 REST 到内部 gRPC 的转换。
