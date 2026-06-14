# 用户登录服务 Java 后端技术方案

## 1. 文档说明

本文档用于设计一个 Java 后端用户登录服务，服务名暂定为：

```text
user-service
```

本服务主要负责：

1. 手机号验证码登录；
2. Google 第三方登录；
3. 快速登录；
4. 登录后活体检测；
5. 注册资料补全；
6. 头像上传到 MinIO；
7. 头像异步真人识别和颜值分计算；
8. 用户基础资料更新；
9. 用户标签管理；
10. BH 真人用户和 DH 数字人用户区分；
11. 对其他微服务提供 gRPC 查询接口。

---

## 2. 已确认需求

### 2.1 登录方式

系统支持三种登录方式：

```text
1. 快速登录
2. 手机号登录
3. 第三方登录
```

具体定义如下：

| 登录方式 | 设计方案 |
|---|---|
| 快速登录 | refresh_token + device_id |
| 手机号登录 | 手机号 + 短信验证码 |
| 第三方登录 | MVP 阶段只支持 Google 登录 |

---

### 2.2 活体检测

用户登录凭证校验通过后，不直接视为正式登录成功。

正式登录成功需要满足：

```text
登录凭证校验通过
+ 活体检测通过
+ 必填基础资料补全
```

活体检测规则：

```text
1. 首次注册必须活体检测；
2. 换设备、高风险登录、账号异常时需要重新活体检测；
3. 普通快速登录不需要每次活体检测；
4. 活体检测失败后退回登录页面。
```

---

### 2.3 资料补全

活体检测通过后，用户需要补全以下资料：

```text
昵称
性别
生日
种族
头像
```

种族只允许三种：

```text
BLACK   黑
WHITE   白
YELLOW  黄
```

数据库中只存编码，不直接存中文。

---

### 2.4 头像识别

头像上传后：

```text
1. 头像先上传到 MinIO；
2. 保存 avatar_url 和 avatar_object_key；
3. 头像审核状态设置为 PENDING；
4. 发送 RocketMQ 消息；
5. 异步调用第三方 API 判断是否真人；
6. 异步计算颜值分；
7. 更新 user_profile 的 avatar_audit_status、is_real_person、face_score。
```

头像审核失败后：

```text
1. 不强制退出登录；
2. 但限制核心社交功能；
3. 用户可以重新上传头像。
```

默认限制功能：

```text
不能进入匹配池
不能主动发起私信
不能发布动态
可以浏览基础内容
可以重新上传头像
```

---

### 2.5 用户资料更新

登录成功后，用户可以更新：

```text
Tags
所在城市
Bio
职业
身高
体重
教育背景
MBTI
```

默认规则：

```text
昵称和头像允许修改；
性别、生日、种族不允许普通用户频繁修改；
如果后续需要修改性别、生日、种族，建议单独做审核接口。
```

---

### 2.6 用户类型

用户表新增字段：

```text
user_type
```

枚举值：

```text
BH = 真人用户
DH = 数字人用户
```

默认规则：

```text
1. 普通用户注册时默认 user_type = BH；
2. BH 用户需要活体检测；
3. DH 数字人不需要活体检测；
4. DH 数字人普通用户不能注册；
5. DH 数字人 MVP 阶段可以通过受控 SQL 脚本创建；
6. 正式环境后续建议提供后台管理接口创建 DH。
```

---

## 3. 技术选型

| 模块 | 技术 |
|---|---|
| 后端语言 | Java 21 |
| 后端框架 | Spring Boot 3.x |
| 项目结构 | controller / service / model / repository |
| ORM | MyBatis-Plus |
| 数据库 | PostgreSQL |
| 缓存 | Redis |
| 文件存储 | MinIO |
| 消息队列 | RocketMQ |
| 业务 ID | 雪花算法（转为字符串存储） |
| 外部入口 | Gateway |
| 内部通信 | gRPC |
| Token | JWT + refresh_token（login_session_token 使用随机字符串 + Redis） |
| 第三方登录 | Google |
| 活体检测 | 第三方 API |
| 头像识别 | 第三方 API |

---

## 4. 总体架构

```text
App / Web
   ↓
API Gateway
   ↓
user-service HTTP Controller
   ↓
Service
   ↓
Repository
   ↓
PostgreSQL
```

内部微服务调用：

```text
match-service / im-service / recommend-service
   ↓ gRPC
user-service
```

头像异步审核：

```text
user-service
   ↓
RocketMQ
   ↓
AvatarAuditConsumer
   ↓
第三方头像识别 API
   ↓
PostgreSQL
```

---

## 5. user-service 职责边界

user-service 负责：

```text
用户登录
用户注册
活体检测状态管理
登录会话管理
Token 签发
refresh_token 管理
用户资料管理
头像上传
头像异步审核
标签选择
BH / DH 用户类型管理
对其他服务提供用户基础信息查询
```

user-service 不负责：

```text
匹配算法
IM 聊天
推荐算法
动态内容
支付
运营后台完整权限系统
```

---

## 6. MVC 项目结构

```text
user-service
├── src/main/java/com/dating/user
│   ├── UserServiceApplication.java
│   │
│   ├── controller
│   │   ├── AuthController.java
│   │   ├── LivenessController.java
│   │   ├── UserProfileController.java
│   │   ├── AvatarController.java
│   │   ├── request
│   │   │   ├── PhoneLoginRequest.java
│   │   │   ├── GoogleLoginRequest.java
│   │   │   ├── QuickLoginRequest.java
│   │   │   ├── CompleteProfileRequest.java
│   │   │   ├── SendSmsCodeRequest.java
│   │   │   └── UpdateUserProfileRequest.java
│   │   └── response
│   │       ├── LoginSessionResponse.java
│   │       ├── LivenessStartResponse.java
│   │       ├── LivenessVerifyResponse.java
│   │       ├── CompleteProfileResponse.java
│   │       └── UserProfileResponse.java
│   │
│   ├── service
│   │   ├── AuthService.java
│   │   ├── SmsCodeService.java
│   │   ├── LoginSessionService.java
│   │   ├── TokenService.java
│   │   ├── LivenessService.java
│   │   ├── UserProfileService.java
│   │   ├── AvatarService.java
│   │   └── UserGrpcQueryService.java
│   │
│   ├── repository
│   │   ├── UserRepository.java
│   │   ├── UserAuthIdentityRepository.java
│   │   ├── UserProfileRepository.java
│   │   ├── LoginSessionRepository.java
│   │   ├── LivenessRecordRepository.java
│   │   ├── UserDeviceRepository.java
│   │   ├── UserRefreshTokenRepository.java
│   │   ├── TagRepository.java
│   │   ├── UserTagRepository.java
│   │   └── SmsCodeRepository.java
│   │
│   ├── model
│   │   ├── UserEntity.java
│   │   ├── UserAuthIdentityEntity.java
│   │   ├── UserProfileEntity.java
│   │   ├── LoginSessionEntity.java
│   │   ├── LivenessRecordEntity.java
│   │   ├── UserDeviceEntity.java
│   │   ├── UserRefreshTokenEntity.java
│   │   ├── TagEntity.java
│   │   ├── UserTagEntity.java
│   │   └── SmsCodeEntity.java
│   │
│   ├── enums
│   │   ├── UserTypeEnum.java
│   │   ├── UserStatusEnum.java
│   │   ├── LoginTypeEnum.java
│   │   ├── LoginSessionStatusEnum.java
│   │   ├── LivenessStatusEnum.java
│   │   ├── AvatarAuditStatusEnum.java
│   │   ├── GenderEnum.java
│   │   ├── RaceCodeEnum.java
│   │   └── NextStepEnum.java
│   │
│   ├── grpc
│   │   ├── UserGrpcController.java
│   │   └── UserGrpcConverter.java
│   │
│   ├── client
│   │   ├── LivenessClient.java
│   │   ├── FaceAnalyzeClient.java
│   │   └── SmsClient.java
│   │
│   ├── storage
│   │   └── MinioStorageService.java
│   │
│   ├── security
│   │   ├── JwtTokenProvider.java
│   │   ├── LoginSessionTokenProvider.java
│   │   ├── UserContext.java
│   │   ├── UserContextHolder.java
│   │   └── TokenInterceptor.java
│   │
│   ├── config
│   │   ├── MybatisPlusConfig.java
│   │   ├── MinioConfig.java
│   │   ├── RocketMqConfig.java
│   │   ├── RedisConfig.java
│   │   ├── GrpcServerConfig.java
│   │   └── WebMvcConfig.java
│   │
│   └── exception
│       ├── ErrorCode.java
│       ├── UserServiceException.java
│       └── GlobalExceptionHandler.java
│
└── src/main/resources
    ├── application.yml
    ├── mapper
    └── db
        ├── migration
        └── script
```

---

## 7. 核心业务流程

### 7.1 手机号登录流程

```text
用户输入手机号和验证码
    ↓
后端校验验证码
    ↓
根据手机号查询用户
    ↓
用户不存在则创建 BH 用户
    ↓
绑定 PHONE 登录身份
    ↓
创建 login_session
    ↓
返回 login_session_token
    ↓
前端进入活体检测
```

---

### 7.2 Google 登录流程

```text
用户使用 Google 登录
    ↓
Gateway 校验 Google id_token
    ↓
Gateway 获取 Google 用户唯一 ID
    ↓
Gateway 通过 gRPC 调用 user-service
    ↓
查询 user_auth_identities
    ↓
不存在则创建 BH 用户，绑定 GOOGLE 身份
    ↓
存在则直接使用已有用户
    ↓
创建 login_session
    ↓
返回 login_session_token
    ↓
前端进入活体检测
```

---

### 7.3 快速登录流程

```text
用户打开 App
    ↓
前端提交 device_id + refresh_token
    ↓
后端校验 refresh_token_hash
    ↓
校验设备是否匹配
    ↓
校验用户状态
    ↓
判断是否高风险
    ↓
低风险：签发新的 access_token + refresh_token
高风险：返回 LIVENESS_REQUIRED
```

---

### 7.4 活体检测流程

```text
前端携带 login_session_token 发起活体检测
    ↓
后端调用第三方活体检测 API
    ↓
前端完成活体检测
    ↓
前端提交活体检测结果
    ↓
后端向第三方校验检测结果
    ↓
活体失败：返回 BACK_TO_LOGIN
    ↓
活体成功：判断资料是否完整
    ↓
资料完整：签发正式 Token
资料不完整：返回 PROFILE_REQUIRED
```

---

### 7.5 资料补全流程

```text
用户活体检测通过
    ↓
前端进入资料补全页面
    ↓
用户填写昵称、性别、生日、种族、头像
    ↓
头像先上传到 MinIO
    ↓
前端提交资料补全请求
    ↓
后端保存 user_profile
    ↓
更新 users.profile_completed = true
    ↓
发送头像审核 RocketMQ 消息
    ↓
签发 access_token + refresh_token
```

---

### 7.6 头像异步审核流程

```text
头像上传成功
    ↓
user_profile.avatar_audit_status = PENDING
    ↓
发送 RocketMQ 消息
    ↓
AvatarAuditConsumer 消费消息
    ↓
调用第三方头像识别 API
    ↓
判断是否真人
    ↓
计算颜值分
    ↓
更新 user_profile：avatar_audit_status、is_real_person、face_score
```

---

## 8. Token 设计

### 8.1 login_session_token

`login_session_token` 表示：

```text
用户登录凭证已经校验通过，但还没有正式登录成功。
```

实现方式：

```text
随机字符串（UUID），存储在 Redis 中，key 为 login_session_token，value 为 session_id。
不采用 JWT，因为 login_session_token 生命周期短、需要支持主动失效。
```

允许访问：

```text
POST /api/v1/auth/liveness/start
POST /api/v1/auth/liveness/verify
POST /api/v1/users/profile/complete
POST /api/v1/users/avatar/upload
```

不允许访问：

```text
GET /api/v1/users/me
PUT /api/v1/users/profile
业务核心接口
```

有效期建议：

```text
10 分钟
```

token 示例：

```text
"a1b2c3d4-e5f6-7890-abcd-ef1234567890"
```

Redis 存储结构：

```text
Key: login_session:<token>
Value: session_id
TTL: 10 分钟
```

---

### 8.2 access_token

`access_token` 表示用户已经正式登录成功。

有效期建议：

```text
2 小时
```

JWT 内容示例：

```json
{
  "userId": "10000000001",
  "userType": "BH",
  "tokenType": "ACCESS",
  "exp": 1710000000
}
```

---

### 8.3 refresh_token

`refresh_token` 用于快速登录和刷新 `access_token`。

有效期建议：

```text
30 天
```

存储规则：

```text
客户端保存 refresh_token 明文；
服务端只保存 refresh_token_hash；
数据库不保存明文 refresh_token。
```

---

## 9. 状态枚举设计

### 9.1 用户类型 UserTypeEnum

```java
public enum UserTypeEnum {
    BH, // 真人用户
    DH  // 数字人用户
}
```

---

### 9.2 用户状态 UserStatusEnum

```java
public enum UserStatusEnum {
    ACTIVE,   // 正常
    DISABLED, // 禁用
    FROZEN,   // 冻结
    DELETED   // 注销
}
```

---

### 9.3 登录方式 LoginTypeEnum

```java
public enum LoginTypeEnum {
    PHONE,
    GOOGLE,
    QUICK
}
```

---

### 9.4 登录会话状态 LoginSessionStatusEnum

```java
public enum LoginSessionStatusEnum {
    INIT,
    CREDENTIAL_VERIFIED,
    LIVENESS_REQUIRED,
    LIVENESS_PASSED,
    LIVENESS_FAILED,
    PROFILE_REQUIRED,
    TOKEN_ISSUED,
    EXPIRED
}
```

---

### 9.5 活体检测状态 LivenessStatusEnum

```java
public enum LivenessStatusEnum {
    NOT_REQUIRED,
    PENDING,
    PASSED,
    FAILED
}
```

---

### 9.6 头像审核状态 AvatarAuditStatusEnum

```java
public enum AvatarAuditStatusEnum {
    NONE,
    PENDING,
    PASSED,
    REJECTED,
    FAILED
}
```

---

### 9.7 性别 GenderEnum

```java
public enum GenderEnum {
    MALE,
    FEMALE,
    OTHER
}
```

---

### 9.8 种族 RaceCodeEnum

```java
public enum RaceCodeEnum {
    BLACK,   // 黑
    WHITE,   // 白
    YELLOW   // 黄
}
```

---

### 9.9 下一步动作 NextStepEnum

```java
public enum NextStepEnum {
    LIVENESS_REQUIRED,
    PROFILE_REQUIRED,
    TOKEN_ISSUED,
    BACK_TO_LOGIN,
    ACCOUNT_DISABLED
}
```

---

## 10. PostgreSQL 数据库设计

### 10.1 表设计总览

| 表名 | 说明 |
|---|---|
| users | 用户主表 |
| user_auth_identities | 用户登录身份表 |
| user_profile | 用户资料表 |
| login_session | 登录中间会话表 |
| liveness_record | 活体检测记录表 |
| user_device | 用户设备表 |
| user_refresh_token | refresh_token 表 |
| tags | 标签表 |
| user_tag | 用户标签关系表 |
| sms_code | 短信验证码表 |

---

### 10.2 users 用户主表

```sql
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,
    user_type VARCHAR(16) NOT NULL DEFAULT 'BH',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',

    phone_country_code VARCHAR(8),
    phone VARCHAR(32),

    profile_completed BOOLEAN NOT NULL DEFAULT FALSE,
    liveness_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',

    last_login_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT chk_users_user_type CHECK (user_type IN ('BH', 'DH')),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'DISABLED', 'FROZEN', 'DELETED')),
    CONSTRAINT chk_users_liveness_status CHECK (
        liveness_status IN ('NOT_REQUIRED', 'PENDING', 'PASSED', 'FAILED')
    )
);

CREATE UNIQUE INDEX uk_users_user_id
ON users(user_id);

CREATE UNIQUE INDEX uk_users_phone_active
ON users(phone_country_code, phone)
WHERE deleted = FALSE AND phone IS NOT NULL;
```

说明：

```text
id：数据库内部主键，不对外暴露；
user_id：业务用户 ID，雪花算法生成后转为字符串存储，对外使用；
user_type：BH 真人，DH 数字人；
deleted：逻辑删除；
手机号唯一索引使用 PostgreSQL 部分唯一索引，允许注销后手机号重新注册。
```

---

### 10.3 user_auth_identities 登录身份表

```sql
CREATE TABLE user_auth_identities (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,

    identity_type VARCHAR(32) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    identity_id VARCHAR(128) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT chk_auth_identity_type CHECK (identity_type IN ('PHONE', 'GOOGLE')),
    CONSTRAINT chk_auth_provider CHECK (provider IN ('PHONE', 'GOOGLE'))
);

CREATE UNIQUE INDEX uk_auth_provider_identity_active
ON user_auth_identities(provider, identity_id)
WHERE deleted = FALSE;

CREATE INDEX idx_auth_user_id
ON user_auth_identities(user_id);
```

说明：

```text
手机号登录：identity_id = +86:13800138000
Google 登录：identity_id = Google 用户唯一 ID
```

---

### 10.4 user_profile 用户资料表

```sql
CREATE TABLE user_profile (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,

    nickname VARCHAR(64),
    gender VARCHAR(32),
    birthday DATE,
    race_code VARCHAR(16),

    avatar_object_key VARCHAR(512),
    avatar_url VARCHAR(1024),
    avatar_audit_status VARCHAR(32) NOT NULL DEFAULT 'NONE',
    is_real_person BOOLEAN,
    face_score NUMERIC(5, 2),

    city_code VARCHAR(64),
    city_name VARCHAR(128),
    bio VARCHAR(512),
    occupation VARCHAR(128),
    height_cm INT,
    weight_kg INT,
    education VARCHAR(128),
    mbti VARCHAR(16),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT chk_user_profile_gender CHECK (
        gender IS NULL OR gender IN ('MALE', 'FEMALE', 'OTHER')
    ),

    CONSTRAINT chk_user_profile_race_code CHECK (
        race_code IS NULL OR race_code IN ('BLACK', 'WHITE', 'YELLOW')
    ),

    CONSTRAINT chk_user_profile_avatar_audit_status CHECK (
        avatar_audit_status IN ('NONE', 'PENDING', 'PASSED', 'REJECTED', 'FAILED')
    ),

    CONSTRAINT chk_user_profile_height CHECK (
        height_cm IS NULL OR height_cm BETWEEN 100 AND 250
    ),

    CONSTRAINT chk_user_profile_weight CHECK (
        weight_kg IS NULL OR weight_kg BETWEEN 30 AND 300
    ),

    CONSTRAINT chk_user_profile_face_score CHECK (
        face_score IS NULL OR face_score BETWEEN 0 AND 100
    )
);

CREATE UNIQUE INDEX uk_user_profile_user_id_active
ON user_profile(user_id)
WHERE deleted = FALSE;
```

---

### 10.5 login_session 登录中间会话表

```sql
CREATE TABLE login_session (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL,

    user_id VARCHAR(32),
    login_type VARCHAR(32) NOT NULL,
    device_id VARCHAR(128),

    status VARCHAR(32) NOT NULL,
    liveness_required BOOLEAN NOT NULL DEFAULT TRUE,
    liveness_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',

    fail_reason VARCHAR(512),
    expire_at TIMESTAMPTZ NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_login_session_login_type CHECK (
        login_type IN ('PHONE', 'GOOGLE', 'QUICK')
    ),

    CONSTRAINT chk_login_session_status CHECK (
        status IN (
            'INIT',
            'CREDENTIAL_VERIFIED',
            'LIVENESS_REQUIRED',
            'LIVENESS_PASSED',
            'LIVENESS_FAILED',
            'PROFILE_REQUIRED',
            'TOKEN_ISSUED',
            'EXPIRED'
        )
    ),

    CONSTRAINT chk_login_session_liveness_status CHECK (
        liveness_status IN ('NOT_REQUIRED', 'PENDING', 'PASSED', 'FAILED')
    )
);

CREATE UNIQUE INDEX uk_login_session_id
ON login_session(session_id);

CREATE INDEX idx_login_session_user_id
ON login_session(user_id);

CREATE INDEX idx_login_session_expire_at
ON login_session(expire_at);
```

---

### 10.6 liveness_record 活体检测记录表

```sql
CREATE TABLE liveness_record (
    id BIGSERIAL PRIMARY KEY,
    liveness_id VARCHAR(128) NOT NULL,

    session_id VARCHAR(128) NOT NULL,
    user_id VARCHAR(32) NOT NULL,

    provider VARCHAR(64) NOT NULL,
    provider_request_id VARCHAR(128),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',

    score NUMERIC(5, 2),
    fail_reason VARCHAR(512),
    raw_result JSONB,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_liveness_record_status CHECK (
        status IN ('PENDING', 'PASSED', 'FAILED')
    )
);

CREATE UNIQUE INDEX uk_liveness_id
ON liveness_record(liveness_id);

CREATE INDEX idx_liveness_user_id
ON liveness_record(user_id);

CREATE INDEX idx_liveness_session_id
ON liveness_record(session_id);
```

---

### 10.7 user_device 用户设备表

```sql
CREATE TABLE user_device (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,

    device_id VARCHAR(128) NOT NULL,
    device_type VARCHAR(32),
    device_name VARCHAR(128),
    app_version VARCHAR(64),

    last_login_ip VARCHAR(64),
    last_login_at TIMESTAMPTZ,

    trusted BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT chk_user_device_type CHECK (
        device_type IS NULL OR device_type IN ('IOS', 'ANDROID', 'WEB')
    )
);

CREATE UNIQUE INDEX uk_user_device_active
ON user_device(user_id, device_id)
WHERE deleted = FALSE;

CREATE INDEX idx_user_device_device_id
ON user_device(device_id);
```

---

### 10.8 user_refresh_token 刷新令牌表

```sql
CREATE TABLE user_refresh_token (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,

    device_id VARCHAR(128) NOT NULL,
    refresh_token_hash VARCHAR(256) NOT NULL,

    expire_at TIMESTAMPTZ NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uk_refresh_token_user
ON user_refresh_token(user_id);

CREATE INDEX idx_refresh_token_hash
ON user_refresh_token(refresh_token_hash);

CREATE INDEX idx_refresh_token_expire_at
ON user_refresh_token(expire_at);
```

说明：

```text
1. 每个用户只能保留一个有效的 refresh_token（uk_refresh_token_user）；
2. 新设备登录时，旧设备的 refresh_token 自动作废（revoked = true）；
3. 旧设备下次使用快速登录时会收到 LIVENESS_REQUIRED，需要重新验证身份。
```

---

### 10.9 tags 标签表

```sql
CREATE TABLE tags (
    id BIGSERIAL PRIMARY KEY,
    tag_id VARCHAR(32) NOT NULL,

    category VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT chk_tags_status CHECK (
        status IN ('ACTIVE', 'DISABLED')
    )
);

CREATE UNIQUE INDEX uk_tags_tag_id
ON tags(tag_id);

CREATE UNIQUE INDEX uk_tags_category_name_active
ON tags(category, name)
WHERE deleted = FALSE;
```

---

### 10.10 user_tag 用户标签关系表

```sql
CREATE TABLE user_tag (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(32) NOT NULL,
    tag_id VARCHAR(32) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uk_user_tag
ON user_tag(user_id, tag_id);

CREATE INDEX idx_user_tag_user_id
ON user_tag(user_id);

CREATE INDEX idx_user_tag_tag_id
ON user_tag(tag_id);
```

---

### 10.11 sms_code 短信验证码表

```sql
CREATE TABLE sms_code (
    id BIGSERIAL PRIMARY KEY,

    phone_country_code VARCHAR(8) NOT NULL,
    phone VARCHAR(32) NOT NULL,
    scene VARCHAR(32) NOT NULL,

    code_hash VARCHAR(256) NOT NULL,
    expire_at TIMESTAMPTZ NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,

    send_ip VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_sms_code_scene CHECK (
        scene IN ('LOGIN')
    )
);

CREATE INDEX idx_sms_code_phone_scene
ON sms_code(phone_country_code, phone, scene);

CREATE INDEX idx_sms_code_expire_at
ON sms_code(expire_at);
```

---

## 11. HTTP 接口设计

### 11.1 发送短信验证码

```http
POST /api/v1/auth/sms/send
```

请求：

```json
{
  "phoneCountryCode": "+86",
  "phone": "13800138000",
  "scene": "LOGIN"
}
```

响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "cooldownSeconds": 60
  }
}
```

---

### 11.2 手机号验证码登录

```http
POST /api/v1/auth/phone/login
```

请求：

```json
{
  "phoneCountryCode": "+86",
  "phone": "13800138000",
  "smsCode": "123456",
  "deviceId": "device-xxx",
  "deviceType": "ANDROID"
}
```

响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "loginSessionToken": "login-session-token",
    "nextStep": "LIVENESS_REQUIRED"
  }
}
```

---

### 11.3 Google 登录

```http
POST /api/v1/auth/google/login
```

请求：

```json
{
  "provider": "GOOGLE",
  "providerUserId": "google-user-id",
  "deviceId": "device-xxx",
  "deviceType": "ANDROID"
}
```

响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "loginSessionToken": "login-session-token",
    "nextStep": "LIVENESS_REQUIRED"
  }
}
```

---

### 11.4 快速登录

```http
POST /api/v1/auth/quick-login
```

请求：

```json
{
  "deviceId": "device-xxx",
  "refreshToken": "refresh-token"
}
```

响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "accessToken": "access-token",
    "refreshToken": "new-refresh-token",
    "expiresIn": 7200,
    "userId": "10000000001",
    "userType": "BH",
    "profileCompleted": true,
    "avatarAuditStatus": "PASSED",
    "nextStep": "TOKEN_ISSUED"
  }
}
```

高风险时响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "loginSessionToken": "login-session-token",
    "nextStep": "LIVENESS_REQUIRED"
  }
}
```

---

### 11.5 发起活体检测

```http
POST /api/v1/auth/liveness/start
Authorization: Bearer login_session_token
```

请求：

```json
{
  "scene": "LOGIN"
}
```

响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "livenessId": "live-xxx",
    "provider": "third-party-provider",
    "clientParams": {
      "bizToken": "xxx"
    }
  }
}
```

---

### 11.6 校验活体检测结果

```http
POST /api/v1/auth/liveness/verify
Authorization: Bearer login_session_token
```

请求：

```json
{
  "livenessId": "live-xxx",
  "providerResultToken": "xxx"
}
```

活体通过但资料未完成：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "nextStep": "PROFILE_REQUIRED"
  }
}
```

活体通过且资料已完成：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "nextStep": "TOKEN_ISSUED",
    "accessToken": "access-token",
    "refreshToken": "refresh-token",
    "expiresIn": 7200,
    "userId": "10000000001",
    "profileCompleted": true
  }
}
```

活体失败：

```json
{
  "code": 401,
  "message": "Liveness verification failed",
  "data": {
    "nextStep": "BACK_TO_LOGIN"
  }
}
```

---

### 11.7 上传头像

```http
POST /api/v1/users/avatar/upload
Authorization: Bearer login_session_token 或 access_token
Content-Type: multipart/form-data
```

请求：

```text
file: avatar.jpg
```

响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "avatarUrl": "https://cdn.xxx.com/avatar/xxx.jpg",
    "avatarObjectKey": "avatar/2026/06/xxx.jpg",
    "avatarAuditStatus": "PENDING"
  }
}
```

---

### 11.8 补全注册资料

```http
POST /api/v1/users/profile/complete
Authorization: Bearer login_session_token
```

请求：

```json
{
  "nickname": "Tom",
  "gender": "MALE",
  "birthday": "2000-01-01",
  "raceCode": "YELLOW",
  "avatarUrl": "https://cdn.xxx.com/avatar/xxx.jpg",
  "avatarObjectKey": "avatar/2026/06/xxx.jpg"
}
```

响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "accessToken": "access-token",
    "refreshToken": "refresh-token",
    "expiresIn": 7200,
    "userId": "10000000001",
    "userType": "BH",
    "profileCompleted": true,
    "avatarAuditStatus": "PENDING",
    "nextStep": "TOKEN_ISSUED"
  }
}
```

---

### 11.9 更新用户资料

```http
PUT /api/v1/users/profile
Authorization: Bearer access_token
```

请求：

```json
{
  "tags": [10001, 10002],
  "cityCode": "310000",
  "cityName": "上海",
  "bio": "你好，我是 Tom",
  "occupation": "Software Engineer",
  "heightCm": 175,
  "weightKg": 68,
  "education": "Bachelor",
  "mbti": "INTJ"
}
```

响应：

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

---

### 11.10 登出

```http
POST /api/v1/auth/logout
Authorization: Bearer access_token
```

请求：

无需请求体，服务端从 access_token 中解析 user_id。

响应：

```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

说明：

```text
1. 服务端将当前用户的 refresh_token 标记 revoked = true；
2. 当前 access_token 加入 Redis 黑名单（TTL = access_token 剩余有效期）；
3. 由于每个用户只有一个有效 refresh_token，不需要传 deviceId。
```

---

### 11.11 查询当前用户信息

```http
GET /api/v1/users/me
Authorization: Bearer access_token
```

响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "userId": "10000000001",
    "userType": "BH",
    "status": "ACTIVE",
    "nickname": "Tom",
    "gender": "MALE",
    "birthday": "2000-01-01",
    "raceCode": "YELLOW",
    "raceName": "黄",
    "avatarUrl": "https://cdn.xxx.com/avatar/xxx.jpg",
    "avatarAuditStatus": "PASSED",
    "isRealPerson": true,
    "faceScore": 87.5,
    "cityCode": "310000",
    "cityName": "上海",
    "bio": "你好，我是 Tom",
    "occupation": "Software Engineer",
    "heightCm": 175,
    "weightKg": 68,
    "education": "Bachelor",
    "mbti": "INTJ",
    "tags": [
      {
        "tagId": 10001,
        "name": "旅行"
      }
    ]
  }
}
```

---

## 12. DTO 设计

### 12.1 PhoneLoginRequest

```java
@Data
public class PhoneLoginRequest {

    @NotBlank
    private String phoneCountryCode;

    @NotBlank
    private String phone;

    @NotBlank
    private String smsCode;

    @NotBlank
    private String deviceId;

    @NotBlank
    private String deviceType;
}
```

---

### 12.2 GoogleLoginRequest

```java
@Data
public class GoogleLoginRequest {

    @NotBlank
    private String provider;

    @NotBlank
    private String providerUserId;

    @NotBlank
    private String deviceId;

    @NotBlank
    private String deviceType;
}
```

说明：Google id_token 由 Gateway 负责校验，user-service 只接收 Gateway 校验通过后传入的 `providerUserId`。

---

### 12.3 QuickLoginRequest

```java
@Data
public class QuickLoginRequest {

    @NotBlank
    private String deviceId;

    @NotBlank
    private String refreshToken;
}
```

---

### 12.4 CompleteProfileRequest

```java
@Data
public class CompleteProfileRequest {

    @NotBlank
    private String nickname;

    @NotBlank
    private String gender;

    @NotNull
    private LocalDate birthday;

    @NotBlank
    private String raceCode;

    @NotBlank
    private String avatarUrl;

    @NotBlank
    private String avatarObjectKey;
}
```

---

### 12.5 UpdateUserProfileRequest

```java
@Data
public class UpdateUserProfileRequest {

    private List<String> tags;

    private String cityCode;

    private String cityName;

    private String bio;

    private String occupation;

    private Integer heightCm;

    private Integer weightKg;

    private String education;

    private String mbti;
}
```

---

## 13. Controller 设计

### 13.1 AuthController

```java
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SmsCodeService smsCodeService;
    private final AuthService authService;

    @PostMapping("/sms/send")
    public ApiResult<SendSmsCodeResponse> sendSmsCode(
            @RequestBody @Valid SendSmsCodeRequest request
    ) {
        return ApiResult.success(smsCodeService.sendLoginCode(request));
    }

    @PostMapping("/phone/login")
    public ApiResult<LoginSessionResponse> phoneLogin(
            @RequestBody @Valid PhoneLoginRequest request
    ) {
        return ApiResult.success(authService.phoneLogin(request));
    }

    @PostMapping("/google/login")
    public ApiResult<LoginSessionResponse> googleLogin(
            @RequestBody @Valid GoogleLoginRequest request
    ) {
        return ApiResult.success(authService.googleLogin(request));
    }

    @PostMapping("/quick-login")
    public ApiResult<?> quickLogin(
            @RequestBody @Valid QuickLoginRequest request
    ) {
        return ApiResult.success(authService.quickLogin(request));
    }

    @PostMapping("/logout")
    public ApiResult<Void> logout(
            @RequestBody @Valid LogoutRequest request
    ) {
        authService.logout(request);
        return ApiResult.success();
    }
}
```

---

### 13.2 LivenessController

```java
@RestController
@RequestMapping("/api/v1/auth/liveness")
@RequiredArgsConstructor
public class LivenessController {

    private final LivenessService livenessService;

    @PostMapping("/start")
    public ApiResult<LivenessStartResponse> start(
            @RequestBody @Valid LivenessStartRequest request
    ) {
        return ApiResult.success(livenessService.start(request));
    }

    @PostMapping("/verify")
    public ApiResult<LivenessVerifyResponse> verify(
            @RequestBody @Valid LivenessVerifyRequest request
    ) {
        return ApiResult.success(livenessService.verify(request));
    }
}
```

---

### 13.3 UserProfileController

```java
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;

    @PostMapping("/profile/complete")
    public ApiResult<CompleteProfileResponse> completeProfile(
            @RequestBody @Valid CompleteProfileRequest request
    ) {
        return ApiResult.success(userProfileService.completeProfile(request));
    }

    @PutMapping("/profile")
    public ApiResult<Void> updateProfile(
            @RequestBody @Valid UpdateUserProfileRequest request
    ) {
        userProfileService.updateProfile(request);
        return ApiResult.success();
    }

    @GetMapping("/me")
    public ApiResult<UserProfileResponse> getCurrentUser() {
        return ApiResult.success(userProfileService.getCurrentUser());
    }
}
```

---

### 13.4 AvatarController

```java
@RestController
@RequestMapping("/api/v1/users/avatar")
@RequiredArgsConstructor
public class AvatarController {

    private final AvatarService avatarService;

    @PostMapping("/upload")
    public ApiResult<UploadAvatarResponse> upload(
            @RequestParam("file") MultipartFile file
    ) {
        return ApiResult.success(avatarService.uploadAvatar(file));
    }
}
```

---

## 14. Service 核心逻辑

### 14.1 AuthService

```java
public interface AuthService {

    LoginSessionResponse phoneLogin(PhoneLoginRequest request);

    LoginSessionResponse googleLogin(GoogleLoginRequest request);

    Object quickLogin(QuickLoginRequest request);

    void logout(LogoutRequest request);
}
```

手机号登录核心逻辑：

```text
校验短信验证码
查询手机号对应用户
不存在则创建 BH 用户
绑定 PHONE 身份
创建设备记录
创建登录中间会话
生成 login_session_token
返回 nextStep = LIVENESS_REQUIRED
```

Google 登录核心逻辑：

```text
接收 Gateway 传入的 provider 和 provider_user_id
查询绑定关系
不存在则创建 BH 用户
绑定 GOOGLE 身份
创建设备记录
创建登录中间会话
生成 login_session_token
返回 nextStep = LIVENESS_REQUIRED
```

身份绑定规则：

```text
1. 一个用户可以有多个登录身份（PHONE + GOOGLE）；
2. Google 登录的首次注册用户，活体检测通过后必须绑定手机号；
3. 手机号登录的用户，登录后可以在设置中绑定 Google 账号；
4. 绑定手机号时，校验手机号是否已被其他用户绑定，如已绑定则拒绝并提示；
5. 通过手机号可以在 user_auth_identities 表中建立 PHONE + GOOGLE 的关联。
```

快速登录核心逻辑：

```text
校验 refresh_token_hash
校验 device_id
校验用户状态
判断是否高风险
低风险则签发新的 access_token + refresh_token
高风险则创建 login_session 并返回 LIVENESS_REQUIRED
```

---

### 14.2 LivenessService

```java
public interface LivenessService {

    LivenessStartResponse start(LivenessStartRequest request);

    LivenessVerifyResponse verify(LivenessVerifyRequest request);
}
```

核心逻辑：

```text
start:
校验 login_session_token（查 Redis）→ 获取 session_id
查询 login_session
创建第三方活体检测任务
保存 liveness_record
返回前端检测参数

verify:
校验 login_session_token（查 Redis）→ 获取 session_id
查询 liveness_record
调用第三方 API 校验结果
失败：更新状态、删除 Redis 中的 login_session_token，返回 BACK_TO_LOGIN
成功：更新状态
判断 profile_completed
已完成：签发正式 Token，删除 Redis 中的 login_session_token
未完成：返回 PROFILE_REQUIRED
```

---

### 14.3 UserProfileService

```java
public interface UserProfileService {

    CompleteProfileResponse completeProfile(CompleteProfileRequest request);

    void updateProfile(UpdateUserProfileRequest request);

    UserProfileResponse getCurrentUser();
}
```

资料补全逻辑：

```text
校验 login_session_token（查 Redis 获取 session_id）
确认活体检测已通过
校验昵称、性别、生日、种族、头像
保存 user_profile
更新 users.profile_completed = true
发送 RocketMQ 头像审核消息（头像审核由 user_profile 的 avatar_audit_status 字段管理）
签发正式 access_token + refresh_token
```

更新资料逻辑：

```text
校验 access_token
获取 user_id
校验用户状态
校验 tags 是否存在且启用
更新 user_profile 扩展字段
删除旧 user_tag
批量插入新 user_tag
```

---

### 14.4 AvatarService

```java
public interface AvatarService {

    UploadAvatarResponse uploadAvatar(MultipartFile file);
}
```

核心逻辑：

```text
校验文件大小
校验文件类型
生成 MinIO object key
上传文件到 MinIO
生成 avatar_url
返回 avatar_url 和 avatar_object_key
```

---

## 15. MinIO 设计

### 15.1 Bucket

建议 bucket：

```text
dating-app
```

头像 object key 示例：

```text
avatar/{yyyy}/{MM}/{dd}/{userId}/{uuid}.jpg
```

例如：

```text
avatar/2026/06/14/10000000001/b5a7c8.jpg
```

---

### 15.2 文件校验规则

```text
文件类型：jpg、jpeg、png、webp
文件大小：不超过 5MB
图片宽高：建议不低于 300x300
```

---

## 16. RocketMQ 设计

### 16.1 Topic

```text
avatar-audit-topic
```

---

### 16.2 消息体

```java
@Data
@Builder
public class AvatarAuditMessage {

    private String userId;

    private String avatarObjectKey;

    private String avatarUrl;

    private Integer retryCount;
}
```

说明：

```text
审核消费端更新 user_profile 时需要比对 avatar_object_key：一致才写入审核结果，不一致则丢弃（用户已重新上传头像）。
```

---

### 16.3 发送时机

```text
用户补全资料时
用户修改头像时
```

---

### 16.4 消费逻辑

```text
收到 avatar-audit-topic 消息
    ↓
根据 user_id 查询 user_profile
    ↓
如果 avatar_audit_status 已经是 PASSED / REJECTED，直接跳过（幂等）
    ↓
调用第三方头像识别 API
    ↓
识别是否真人
    ↓
计算颜值分
    ↓
更新 user_profile：avatar_audit_status、is_real_person、face_score
```

---

### 16.5 幂等规则

```text
同一个头像的审核消息可以重复消费，通过判断 avatar_audit_status 是否为终态（PASSED / REJECTED）实现幂等。
```

处理前判断：

```text
audit_status 是否已经是 PASSED 或 REJECTED
```

如果是终态，直接跳过。

---

## 17. gRPC 设计

### 17.1 Proto

```proto
syntax = "proto3";

package dating.user.v1;

option java_package = "com.dating.user.grpc.v1";
option java_multiple_files = true;
option java_outer_classname = "UserServiceProto";

service UserService {
  rpc GetUserBasicInfo(GetUserBasicInfoRequest) returns (GetUserBasicInfoResponse);
  rpc BatchGetUserBasicInfo(BatchGetUserBasicInfoRequest) returns (BatchGetUserBasicInfoResponse);
  rpc CheckUserStatus(CheckUserStatusRequest) returns (CheckUserStatusResponse);
  rpc CheckUserAvatarAuditStatus(CheckUserAvatarAuditStatusRequest) returns (CheckUserAvatarAuditStatusResponse);
}

message GetUserBasicInfoRequest {
  string user_id = 1;
}

message GetUserBasicInfoResponse {
  UserBasicInfo user = 1;
}

message BatchGetUserBasicInfoRequest {
  repeated string user_ids = 1;
}

message BatchGetUserBasicInfoResponse {
  repeated UserBasicInfo users = 1;
}

message CheckUserStatusRequest {
  string user_id = 1;
}

message CheckUserStatusResponse {
  string user_id = 1;
  string status = 2;
  bool available = 3;
}

message CheckUserAvatarAuditStatusRequest {
  string user_id = 1;
}

message CheckUserAvatarAuditStatusResponse {
  string user_id = 1;
  string user_type = 2;
  string avatar_audit_status = 3;
  bool is_real_person = 4;
  bool avatar_available = 5;
}

message UserBasicInfo {
  string user_id = 1;
  string user_type = 2;
  string status = 3;

  string nickname = 4;
  string gender = 5;
  string avatar_url = 6;
  string avatar_audit_status = 7;
  bool is_real_person = 8;

  string city_code = 9;
  string city_name = 10;
  string bio = 11;
}
```

---

### 17.2 gRPC 接口用途

| 方法 | 用途 |
|---|---|
| GetUserBasicInfo | 查询单个用户基础信息 |
| BatchGetUserBasicInfo | 批量查询用户基础信息 |
| CheckUserStatus | 判断用户是否可用 |
| CheckUserAvatarAuditStatus | 判断头像是否可用 |

---

### 17.3 头像可用判断规则

BH 真人用户：

```text
avatar_audit_status = PASSED
且 is_real_person = true
```

DH 数字人用户：

```text
avatar_audit_status = PASSED
```

伪代码：

```java
public boolean isAvatarAvailable(
        String userType,
        String avatarAuditStatus,
        Boolean isRealPerson
) {
    if ("DH".equals(userType)) {
        return "PASSED".equals(avatarAuditStatus);
    }

    return "PASSED".equals(avatarAuditStatus)
            && Boolean.TRUE.equals(isRealPerson);
}
```

---

## 18. DH 数字人设计

### 18.1 DH 数字人规则

```text
1. 普通用户不能注册 DH；
2. DH 不需要活体检测；
3. DH 默认不需要登录；
4. DH 默认不需要 user_auth_identities；
5. DH 默认不需要 user_device；
6. DH 默认不需要 user_refresh_token；
7. DH 可以通过受控 SQL 脚本创建；
8. 正式环境后续建议通过后台管理接口创建。
```

---

### 18.2 DH 最小数据要求

```text
users.user_type = DH
users.status = ACTIVE
users.profile_completed = true
users.liveness_status = NOT_REQUIRED
```

user_profile 至少需要：

```text
nickname
gender
birthday
race_code
```

如果 DH 要参与展示、匹配、聊天，建议也必须有头像：

```text
avatar_url
avatar_object_key
avatar_audit_status = PASSED
is_real_person = false
```

---

### 18.3 DH 创建 SQL 示例

```sql
BEGIN;

INSERT INTO users (
    user_id,
    user_type,
    status,
    profile_completed,
    liveness_status,
    created_at,
    updated_at,
    deleted
) VALUES (
    '900000000000000001',
    'DH',
    'ACTIVE',
    TRUE,
    'NOT_REQUIRED',
    NOW(),
    NOW(),
    FALSE
);

INSERT INTO user_profile (
    user_id,
    nickname,
    gender,
    birthday,
    race_code,
    avatar_object_key,
    avatar_url,
    avatar_audit_status,
    is_real_person,
    face_score,
    city_code,
    city_name,
    bio,
    occupation,
    height_cm,
    weight_kg,
    education,
    mbti,
    created_at,
    updated_at,
    deleted
) VALUES (
    '900000000000000001',
    '数字人-Alice',
    'FEMALE',
    '2000-01-01',
    'YELLOW',
    'avatar/digital-human/alice.png',
    'https://cdn.xxx.com/avatar/digital-human/alice.png',
    'PASSED',
    FALSE,
    NULL,
    '310000',
    '上海',
    '我是数字人 Alice',
    'Digital Human',
    168,
    50,
    'Bachelor',
    'ENFP',
    NOW(),
    NOW(),
    FALSE
);

COMMIT;
```

---

## 19. 安全设计

### 19.1 短信验证码安全

需要做：

```text
手机号发送频率限制
IP 发送频率限制
设备发送频率限制
验证码过期时间
验证码使用后失效
验证码 hash 存储
```

建议：

```text
验证码有效期：5 分钟
发送冷却：60 秒
同手机号每日发送次数限制
```

---

### 19.2 refresh_token 安全

规则：

```text
1. refresh_token 只在创建时返回给客户端；
2. 服务端只保存 refresh_token_hash；
3. 每次使用 refresh_token 刷新 access_token 时，同时轮换 refresh_token（返回新的 refresh_token，旧的立即作废）；
4. 新设备登录后旧 refresh_token 自动作废；
5. 退出登录时 revoked = true；
6. 每个用户同一时间只有一个有效 refresh_token。
```

---

### 19.3 login_session_token 安全

规则：

```text
1. login_session_token 使用随机字符串（UUID），存储在 Redis 中；
2. 有效期短（10 分钟）；
3. 只能访问登录流程接口；
4. 不能访问正式业务接口；
5. 活体失败后立即从 Redis 删除；
6. 资料补全成功后立即从 Redis 删除。
```

---

### 19.4 Gateway 透传身份

Gateway 校验正式 `access_token` 后，向 user-service 传：

```http
X-User-Id: "10000000001"
X-User-Type: BH
X-Device-Id: device-xxx
```

user-service 不信任前端直接传来的 `X-User-Id`。

---

## 20. 错误码设计

### 20.1 响应结构

ApiResult 统一返回结构：

```java
@Data
public class ApiResult<T> {

    private int code;       // 业务状态码，0 表示成功，非 0 表示异常
    private String message; // 提示信息
    private T data;         // 业务数据（成功时有值）
}
```

### 20.2 HTTP 状态码与业务 code 映射

核心原则：**业务流程的正常分支统一返回 HTTP 200 + nextStep 表达当前所处阶段，只有认证/授权/系统故障才使用 HTTP 非 200 状态码。**

| 场景 | HTTP 状态码 | 业务 code | 说明 |
|------|-----------|----------|------|
| 成功 | 200 | 0 | 正常返回 |
| 业务流程分支（需要活体检测、资料补全等） | 200 | 0 | 通过 `nextStep` 字段区分，不视为错误 |
| 业务异常（验证码错误、活体失败等） | 200 | 10001-10015 | 业务逻辑错误，HTTP 层面仍视为成功响应 |
| 参数校验失败 | 400 | 400 | Spring Validation 自动处理 |
| 未认证（access_token 无效） | 401 | 401 | 无有效身份凭证 |
| 系统异常 | 500 | 500 | 非预期错误，由 GlobalExceptionHandler 兜底 |

### 20.3 业务错误码

| 业务 code | 含义 |
|---|---|
| 0 | 成功 |
| 10001 | 短信验证码错误 |
| 10002 | 短信验证码过期 |
| 10003 | 登录会话过期 |
| 10004 | 活体检测失败 |
| 10005 | refresh_token 无效 |
| 10006 | 用户已被冻结 |
| 10007 | 用户不存在 |
| 10008 | 用户状态异常 |
| 10009 | 标签不存在或已禁用 |
| 10010 | 头像上传失败（文件类型/大小不合法） |
| 10011 | MinIO 服务异常 |
| 10012 | RocketMQ 消息发送失败 |
| 10013 | 手机号已被其他用户绑定 |
| 10014 | 发送验证码过于频繁 |
| 10015 | 头像审核未通过 |

说明：去掉了原来与 HTTP 状态码重复的 200/400/401/403/409/429/500，以及不再需要的 10005（用户资料未完成）、10009（第三方登录校验失败，由 Gateway 负责）。

---

## 21. 字段校验规则

### 21.1 昵称

```text
长度：2 ~ 30
禁止敏感词
禁止纯特殊字符
```

---

### 21.2 性别

```text
MALE
FEMALE
OTHER
```

---

### 21.3 生日

```text
不能大于当前日期
年龄不能小于业务允许范围
年龄不能超过 120 岁
```

---

### 21.4 种族

```text
BLACK
WHITE
YELLOW
```

因为种族字段比较敏感，建议：

```text
1. 只用于业务必要场景；
2. 默认不在公开资料中直接展示；
3. 对外展示时由产品决定；
4. 后续如有合规要求，可以改成选填。
```

---

### 21.5 头像

```text
类型：jpg、jpeg、png、webp
大小：不超过 5MB
建议尺寸：不低于 300x300
```

---

### 21.6 身高

```text
100 ~ 250 cm
```

---

### 21.7 体重

```text
30 ~ 300 kg
```

---

### 21.8 MBTI

```text
INTJ
INTP
ENTJ
ENTP
INFJ
INFP
ENFJ
ENFP
ISTJ
ISFJ
ESTJ
ESFJ
ISTP
ISFP
ESTP
ESFP
```

---

## 22. 开发顺序建议

### 第一阶段：基础工程

```text
1. 创建 user-service 工程；
2. 接入 Spring Boot；
3. 接入 PostgreSQL；
4. 接入 MyBatis-Plus；
5. 创建基础返回结构 ApiResult；
6. 创建全局异常处理；
7. 创建枚举类；
8. 创建数据库表。
```

---

### 第二阶段：登录主链路

```text
1. 短信验证码发送；
2. 短信验证码校验；
3. 手机号登录；
4. Google 登录；
5. 用户创建；
6. user_auth_identities 绑定；
7. login_session_token 生成；
8. login_session 状态管理。
```

---

### 第三阶段：活体检测

```text
1. LivenessClient 封装；
2. 发起活体检测；
3. 保存 liveness_record；
4. 校验活体结果；
5. 活体失败返回登录页；
6. 活体成功进入资料补全。
```

---

### 第四阶段：资料补全与正式 Token

```text
1. 头像上传到 MinIO；
2. 资料补全接口；
3. 更新 profile_completed；
4. 签发 access_token；
5. 签发 refresh_token；
6. 保存 refresh_token_hash；
7. 快速登录。
```

---

### 第五阶段：头像异步审核

```text
1. 发送 RocketMQ 消息；
2. 编写 AvatarAuditConsumer；
3. 调用第三方头像识别 API；
4. 更新 user_profile 头像审核状态；
5. 更新颜值分和 is_real_person；
6. 实现幂等和重试。
```

---

### 第六阶段：用户资料更新

```text
1. 更新城市；
2. 更新 Bio；
3. 更新职业；
4. 更新身高体重；
5. 更新教育背景；
6. 更新 MBTI；
7. 更新 Tags；
8. 查询当前用户信息。
```

---

### 第七阶段：gRPC

```text
1. 编写 user_service.proto；
2. 生成 Java gRPC 代码；
3. 实现 UserGrpcController；
4. 实现单用户查询；
5. 实现批量用户查询；
6. 实现用户状态校验；
7. 实现头像可用性校验。
```

---

### 第八阶段：数字人

```text
1. 准备 DH 创建 SQL 脚本；
2. 插入 users；
3. 插入 user_profile；
4. 校验 gRPC 查询是否正常；
5. 校验头像可用判断逻辑；
6. 后续再考虑后台管理接口。
```

---

## 23. 当前不阻塞开发的后续确认项

以下内容不影响当前 user-service 主链路开发，可以后续再定：

```text
1. 具体短信服务商；
2. 具体活体检测服务商；
3. 具体头像识别和颜值分服务商；
4. Gateway 的具体认证实现；
5. DH 是否参与匹配；
6. DH 是否需要对用户明确展示数字人标识；
7. 性别、生日、种族后续修改是否需要人工审核；
8. 头像审核失败限制功能是否继续细化到具体业务服务。
```

---

## 24. 总结

本方案最终采用：

```text
语言：Java 21
框架：Spring Boot 3.x
分层：controller / service / model / repository（Service 不拆 interface/impl）
ORM：MyBatis-Plus
数据库：PostgreSQL
缓存：Redis
Token：JWT access_token + refresh_token（login_session_token 使用随机字符串 + Redis）
文件存储：MinIO
消息队列：RocketMQ
ID 生成：雪花算法（转为字符串存储）
外部入口：Gateway（负责 Google id_token 校验）
内部通信：gRPC
活体检测：第三方 API
头像识别：第三方 API
```

核心登录设计是：

```text
登录凭证校验通过
    ↓
生成 login_session_token（随机字符串，存 Redis）
    ↓
活体检测
    ↓
资料补全
    ↓
签发正式 access_token（JWT） + refresh_token
```

核心用户类型设计是：

```text
BH 真人用户：
    需要活体检测
    需要头像真人识别
    普通用户注册默认创建

DH 数字人用户：
    不需要活体检测
    不需要登录身份
    MVP 阶段通过受控 SQL 创建
```

核心头像设计是：

```text
头像先上传到 MinIO
    ↓
RocketMQ 异步审核（avatar_object_key 比对防覆盖）
    ↓
第三方识别是否真人
    ↓
计算颜值分
    ↓
更新 user_profile：avatar_audit_status、is_real_person、face_score
```

核心身份绑定规则是：

```text
一个用户可以有多个登录身份（PHONE + GOOGLE）
Google 登录用户活体检测后必须绑定手机号
手机号已绑其他用户时拒绝绑定
```

核心安全规则是：

```text
refresh_token 每次使用后轮换，旧 token 立即作废
每个用户同一时间只有一个有效 refresh_token
login_session_token 活体失败或资料补全完成后立即删除
HTTP 200 + 业务 code 表达业务异常，非 200 用于认证/系统异常
```
