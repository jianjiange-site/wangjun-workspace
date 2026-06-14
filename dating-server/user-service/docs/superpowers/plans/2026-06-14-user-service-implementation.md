# user-service 登录服务实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 基于 specs/2026-06-14-user-service-login-design.md 完整实现 user-service 登录、活体检测、资料补全、头像异步审核、用户资料管理和 gRPC 查询接口。

**架构：** 传统 MVC 分层（controller → service → repository → model），HTTP REST 入口，gRPC 内部通信，PostgreSQL 持久化，Redis 缓存，MinIO 文件存储，RocketMQ 消息队列。

**技术栈：** Java 21, Spring Boot 3.3.5, MyBatis-Plus, PostgreSQL, Redis, MinIO, RocketMQ, gRPC/Protobuf, Lombok

---

## 状态说明

当前代码库处于骨架状态：
- gRPC proto 和生成代码完整（3 个服务，13 个 RPC）
- gRPC 实现类返回硬编码骨架数据
- `PhoneVerificationCodeService` 是唯一有真实逻辑（Redis 验证码）
- `AuthService` 有基本验证码校验逻辑，但用户创建/Token 签发仍为 TODO
- 所有 Repository 为空接口，Entity 为无注解 POJO
- 无 MyBatis-Plus 依赖，无 MinIO/RocketMQ 依赖
- 测试中引用了已删除的 `PhoneVerificationCodeManager`（manager 包），需要修复

---

### 任务 1：基础设施——添加 MyBatis-Plus 和 MinIO 依赖，配置数据源和外部服务

**文件：**
- 修改：`dating-server/user-service/pom.xml`
- 修改：`dating-server/user-service/src/main/resources/application.yaml`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/config/MybatisPlusConfig.java`

- [ ] **步骤 1：添加 MyBatis-Plus 和 MinIO 依赖到 pom.xml**

修改 `pom.xml`，在 `<dependencies>` 中 `</dependencies>` 之前添加：

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>3.5.9</version>
</dependency>
<dependency>
    <groupId>io.minio</groupId>
    <artifactId>minio</artifactId>
    <version>8.5.10</version>
</dependency>
```

- [ ] **步骤 2：验证依赖可解析**

```bash
cd //wsl.localhost/ubuntu-22.04/home/wangjun/wangjun-workspace/dating-server/user-service
./mvnw dependency:resolve -q
```
预期：BUILD SUCCESS

- [ ] **步骤 3：配置 application.yaml**

修改 `src/main/resources/application.yaml`，覆盖整个文件：

```yaml
spring:
  application:
    name: user-service
  datasource:
    url: jdbc:postgresql://localhost:5432/dating
    username: ${DB_USERNAME:dating}
    password: ${DB_PASSWORD:dating}
    driver-class-name: org.postgresql.Driver
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

mybatis-plus:
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: true
      logic-not-delete-value: false
  configuration:
    map-underscore-to-camel-case: true

minio:
  endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
  access-key: ${MINIO_ACCESS_KEY:minioadmin}
  secret-key: ${MINIO_SECRET_KEY:minioadmin}
  bucket: dating-app

server:
  port: 8080

grpc:
  server:
    port: 9090
```

- [ ] **步骤 4：编写 MybatisPlusConfig**

创建 `src/main/java/com/dating/user/config/MybatisPlusConfig.java`：

```java
package com.dating.user.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.dating.user.repository")
public class MybatisPlusConfig {
}
```

- [ ] **步骤 5：Commit**

```bash
git add pom.xml src/main/resources/application.yaml src/main/java/com/dating/user/config/MybatisPlusConfig.java
git commit -m "feat: add MyBatis-Plus and MinIO dependencies, configure datasource/Redis/MinIO"
```

---

### 任务 2：给 Entity 添加 MyBatis-Plus 注解

**文件：**
- 修改：`dating-server/user-service/src/main/java/com/dating/user/model/UserEntity.java`
- 修改：`dating-server/user-service/src/main/java/com/dating/user/model/UserProfileEntity.java`
- 修改：`dating-server/user-service/src/main/java/com/dating/user/model/UserAuthIdentityEntity.java`
- 修改：`dating-server/user-service/src/main/java/com/dating/user/model/UserSessionEntity.java`
- 修改：`dating-server/user-service/src/main/java/com/dating/user/model/UserLivenessVerificationEntity.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/model/UserDeviceEntity.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/model/UserRefreshTokenEntity.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/model/TagEntity.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/model/UserTagEntity.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/model/SmsCodeEntity.java`

- [ ] **步骤 1：重写 UserEntity**

覆盖 `src/main/java/com/dating/user/model/UserEntity.java`：

```java
package com.dating.user.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("users")
public class UserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;
    private String userType;
    private String status;

    private String phoneCountryCode;
    private String phone;

    private Boolean profileCompleted;
    private String livenessStatus;

    private Instant lastLoginAt;
    private Instant createdAt;
    private Instant updatedAt;

    @TableLogic
    private Boolean deleted;
}
```

- [ ] **步骤 2：重写 UserProfileEntity**

覆盖 `src/main/java/com/dating/user/model/UserProfileEntity.java`：

```java
package com.dating.user.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Data
@TableName("user_profile")
public class UserProfileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;
    private String nickname;
    private String gender;
    private LocalDate birthDate;
    private String raceCode;

    private String avatarObjectKey;
    private String avatarUrl;
    private String avatarAuditStatus;
    private Boolean isRealPerson;
    private BigDecimal faceScore;

    private String cityCode;
    private String cityName;
    private String bio;
    private String occupation;
    private Integer heightCm;
    private Integer weightKg;
    private String education;
    private String mbti;

    private Instant createdAt;
    private Instant updatedAt;

    @TableLogic
    private Boolean deleted;
}
```

- [ ] **步骤 3：重写 UserAuthIdentityEntity**

覆盖 `src/main/java/com/dating/user/model/UserAuthIdentityEntity.java`：

```java
package com.dating.user.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("user_auth_identities")
public class UserAuthIdentityEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;
    private String identityType;
    private String provider;
    private String identityId;

    private Instant createdAt;
    private Instant updatedAt;

    @TableLogic
    private Boolean deleted;
}
```

- [ ] **步骤 4：重写 UserSessionEntity（对齐 login_session 表）**

覆盖 `src/main/java/com/dating/user/model/UserSessionEntity.java`：

```java
package com.dating.user.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("login_session")
public class UserSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;
    private String userId;
    private String loginType;
    private String deviceId;

    private String status;
    private Boolean livenessRequired;
    private String livenessStatus;

    private String failReason;
    private Instant expireAt;

    private Instant createdAt;
    private Instant updatedAt;
}
```

- [ ] **步骤 5：重写 UserLivenessVerificationEntity（对齐 liveness_record 表）**

覆盖 `src/main/java/com/dating/user/model/UserLivenessVerificationEntity.java`：

```java
package com.dating.user.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@TableName("liveness_record")
public class UserLivenessVerificationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String livenessId;
    private String sessionId;
    private String userId;

    private String provider;
    private String providerRequestId;
    private String status;

    private BigDecimal score;
    private String failReason;
    private String rawResult;

    private Instant createdAt;
    private Instant updatedAt;
}
```

- [ ] **步骤 6：创建 UserDeviceEntity**

创建 `src/main/java/com/dating/user/model/UserDeviceEntity.java`：

```java
package com.dating.user.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("user_device")
public class UserDeviceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;
    private String deviceId;
    private String deviceType;
    private String deviceName;
    private String appVersion;

    private String lastLoginIp;
    private Instant lastLoginAt;

    private Boolean trusted;

    private Instant createdAt;
    private Instant updatedAt;

    @TableLogic
    private Boolean deleted;
}
```

- [ ] **步骤 7：创建 UserRefreshTokenEntity**

创建 `src/main/java/com/dating/user/model/UserRefreshTokenEntity.java`：

```java
package com.dating.user.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("user_refresh_token")
public class UserRefreshTokenEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;
    private String deviceId;
    private String refreshTokenHash;

    private Instant expireAt;
    private Boolean revoked;

    private Instant createdAt;
    private Instant updatedAt;
}
```

- [ ] **步骤 8：创建 TagEntity**

创建 `src/main/java/com/dating/user/model/TagEntity.java`：

```java
package com.dating.user.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("tags")
public class TagEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tagId;
    private String category;
    private String name;
    private String status;

    private Instant createdAt;
    private Instant updatedAt;

    @TableLogic
    private Boolean deleted;
}
```

- [ ] **步骤 9：创建 UserTagEntity**

创建 `src/main/java/com/dating/user/model/UserTagEntity.java`：

```java
package com.dating.user.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("user_tag")
public class UserTagEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;
    private String tagId;

    private Instant createdAt;
}
```

- [ ] **步骤 10：创建 SmsCodeEntity**

创建 `src/main/java/com/dating/user/model/SmsCodeEntity.java`：

```java
package com.dating.user.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("sms_code")
public class SmsCodeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String phoneCountryCode;
    private String phone;
    private String scene;

    private String codeHash;
    private Instant expireAt;
    private Boolean used;

    private String sendIp;
    private Instant createdAt;
}
```

- [ ] **步骤 11：Commit**

```bash
git add src/main/java/com/dating/user/model/
git commit -m "feat: add MyBatis-Plus annotations to all entity classes"
```

---

### 任务 3：编写 Repository 接口（继承 MyBatis-Plus BaseMapper）

**文件：**
- 覆盖：`dating-server/user-service/src/main/java/com/dating/user/repository/UserRepository.java`
- 覆盖：`dating-server/user-service/src/main/java/com/dating/user/repository/UserAuthIdentityRepository.java`
- 覆盖：`dating-server/user-service/src/main/java/com/dating/user/repository/UserProfileRepository.java`
- 覆盖：`dating-server/user-service/src/main/java/com/dating/user/repository/UserSessionRepository.java`
- 覆盖：`dating-server/user-service/src/main/java/com/dating/user/repository/UserLivenessVerificationRepository.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/repository/UserDeviceRepository.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/repository/UserRefreshTokenRepository.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/repository/TagRepository.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/repository/UserTagRepository.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/repository/SmsCodeRepository.java`

- [ ] **步骤 1：覆盖 UserRepository**

```java
package com.dating.user.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.model.UserEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserRepository extends BaseMapper<UserEntity> {
}
```

- [ ] **步骤 2：覆盖 UserAuthIdentityRepository**（同样模式）

```java
package com.dating.user.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.model.UserAuthIdentityEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserAuthIdentityRepository extends BaseMapper<UserAuthIdentityEntity> {
}
```

- [ ] **步骤 3：覆盖 UserProfileRepository**

```java
package com.dating.user.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.model.UserProfileEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserProfileRepository extends BaseMapper<UserProfileEntity> {
}
```

- [ ] **步骤 4：覆盖 UserSessionRepository**

```java
package com.dating.user.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.model.UserSessionEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserSessionRepository extends BaseMapper<UserSessionEntity> {
}
```

- [ ] **步骤 5：覆盖 UserLivenessVerificationRepository**

```java
package com.dating.user.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.model.UserLivenessVerificationEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserLivenessVerificationRepository extends BaseMapper<UserLivenessVerificationEntity> {
}
```

- [ ] **步骤 6：创建 UserDeviceRepository**

```java
package com.dating.user.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.model.UserDeviceEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserDeviceRepository extends BaseMapper<UserDeviceEntity> {
}
```

- [ ] **步骤 7：创建 UserRefreshTokenRepository**

```java
package com.dating.user.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.model.UserRefreshTokenEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserRefreshTokenRepository extends BaseMapper<UserRefreshTokenEntity> {
}
```

- [ ] **步骤 8：创建 TagRepository**

```java
package com.dating.user.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.model.TagEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TagRepository extends BaseMapper<TagEntity> {
}
```

- [ ] **步骤 9：创建 UserTagRepository**

```java
package com.dating.user.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.model.UserTagEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserTagRepository extends BaseMapper<UserTagEntity> {
}
```

- [ ] **步骤 10：创建 SmsCodeRepository**

```java
package com.dating.user.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.user.model.SmsCodeEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SmsCodeRepository extends BaseMapper<SmsCodeEntity> {
}
```

- [ ] **步骤 11：Commit**

```bash
git add src/main/java/com/dating/user/repository/
git commit -m "feat: add MyBatis-Plus BaseMapper repository interfaces for all entities"
```

---

### 任务 4：编写雪花 ID 工具类和 ApiResult 统一返回结构

**文件：**
- 创建：`dating-server/user-service/src/main/java/com/dating/user/common/SnowflakeIdGenerator.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/common/ApiResult.java`

- [ ] **步骤 1：编写测试——雪花 ID 生成幂等且唯一**

创建 `src/test/java/com/dating/user/common/SnowflakeIdGeneratorTests.java`：

```java
package com.dating.user.common;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SnowflakeIdGeneratorTests {

    @Test
    void generatesStringIdsThatAreUnique() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 10000; i++) {
            ids.add(generator.nextId());
        }
        assertThat(ids).hasSize(10000);
    }

    @Test
    void generatedIdIsNotEmptyAndNumeric() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1, 1);
        String id = generator.nextId();
        assertThat(id).isNotBlank();
        assertThat(id).matches("\\d+");
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

```bash
cd //wsl.localhost/ubuntu-22.04/home/wangjun/wangjun-workspace/dating-server/user-service
./mvnw test -Dtest=SnowflakeIdGeneratorTests -q
```
预期：FAIL，编译错误 "SnowflakeIdGenerator not found"

- [ ] **步骤 3：实现 SnowflakeIdGenerator**

创建 `src/main/java/com/dating/user/common/SnowflakeIdGenerator.java`：

```java
package com.dating.user.common;

import org.springframework.stereotype.Component;

@Component
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1700000000000L;
    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    private final long workerId;
    private final long datacenterId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator(long workerId, long datacenterId) {
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException("workerId out of range");
        }
        if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0) {
            throw new IllegalArgumentException("datacenterId out of range");
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    public synchronized String nextId() {
        return String.valueOf(nextLong());
    }

    private synchronized long nextLong() {
        long timestamp = currentTimeMillis();
        if (timestamp < lastTimestamp) {
            throw new RuntimeException("Clock moved backwards, refusing to generate id");
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_LEFT_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long tilNextMillis(long lastTimestamp) {
        long timestamp = currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = currentTimeMillis();
        }
        return timestamp;
    }

    private long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

```bash
./mvnw test -Dtest=SnowflakeIdGeneratorTests -q
```
预期：PASS

- [ ] **步骤 5：编写 ApiResult**

创建 `src/main/java/com/dating/user/common/ApiResult.java`：

```java
package com.dating.user.common;

import lombok.Data;

@Data
public class ApiResult<T> {

    private int code;
    private String message;
    private T data;

    private ApiResult(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResult<T> success(T data) {
        return new ApiResult<>(0, "success", data);
    }

    public static <T> ApiResult<T> success() {
        return new ApiResult<>(0, "success", null);
    }

    public static <T> ApiResult<T> error(int code, String message) {
        return new ApiResult<>(code, message, null);
    }
}
```

- [ ] **步骤 6：Commit**

```bash
git add src/main/java/com/dating/user/common/ src/test/java/com/dating/user/common/
git commit -m "feat: add SnowflakeIdGenerator and ApiResult classes

- SnowflakeIdGenerator generates unique string IDs
- ApiResult provides unified REST response envelope (code/message/data)"
```

---

### 任务 5：编写雪花 ID 的 Spring Bean 配置

**文件：**
- 修改：`dating-server/user-service/src/main/java/com/dating/user/config/UserServiceConfig.java`

- [ ] **步骤 1：配置 SnowflakeIdGenerator Bean**

修改 `src/main/java/com/dating/user/config/UserServiceConfig.java`：

```java
package com.dating.user.config;

import com.dating.user.common.SnowflakeIdGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UserServiceConfig {

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator() {
        return new SnowflakeIdGenerator(1, 1);
    }
}
```

- [ ] **步骤 2：Commit**

```bash
git add src/main/java/com/dating/user/config/UserServiceConfig.java
git commit -m "feat: register SnowflakeIdGenerator as Spring bean"
```

---

### 任务 6：实现枚举类

**文件：**
- 创建：`dating-server/user-service/src/main/java/com/dating/user/enums/UserTypeEnum.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/enums/UserStatusEnum.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/enums/LoginTypeEnum.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/enums/LoginSessionStatusEnum.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/enums/LivenessStatusEnum.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/enums/AvatarAuditStatusEnum.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/enums/GenderEnum.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/enums/RaceCodeEnum.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/enums/NextStepEnum.java`

- [ ] **步骤 1：一次性创建所有枚举**

创建 `src/main/java/com/dating/user/enums/UserTypeEnum.java`：

```java
package com.dating.user.enums;

public enum UserTypeEnum {
    BH, DH
}
```

创建 `src/main/java/com/dating/user/enums/UserStatusEnum.java`：

```java
package com.dating.user.enums;

public enum UserStatusEnum {
    ACTIVE, DISABLED, FROZEN, DELETED
}
```

创建 `src/main/java/com/dating/user/enums/LoginTypeEnum.java`：

```java
package com.dating.user.enums;

public enum LoginTypeEnum {
    PHONE, GOOGLE, QUICK
}
```

创建 `src/main/java/com/dating/user/enums/LoginSessionStatusEnum.java`：

```java
package com.dating.user.enums;

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

创建 `src/main/java/com/dating/user/enums/LivenessStatusEnum.java`：

```java
package com.dating.user.enums;

public enum LivenessStatusEnum {
    NOT_REQUIRED, PENDING, PASSED, FAILED
}
```

创建 `src/main/java/com/dating/user/enums/AvatarAuditStatusEnum.java`：

```java
package com.dating.user.enums;

public enum AvatarAuditStatusEnum {
    NONE, PENDING, PASSED, REJECTED, FAILED
}
```

创建 `src/main/java/com/dating/user/enums/GenderEnum.java`：

```java
package com.dating.user.enums;

public enum GenderEnum {
    MALE, FEMALE, OTHER
}
```

创建 `src/main/java/com/dating/user/enums/RaceCodeEnum.java`：

```java
package com.dating.user.enums;

public enum RaceCodeEnum {
    BLACK, WHITE, YELLOW
}
```

创建 `src/main/java/com/dating/user/enums/NextStepEnum.java`：

```java
package com.dating.user.enums;

public enum NextStepEnum {
    LIVENESS_REQUIRED,
    PROFILE_REQUIRED,
    TOKEN_ISSUED,
    BACK_TO_LOGIN,
    ACCOUNT_DISABLED
}
```

- [ ] **步骤 2：Commit**

```bash
git add src/main/java/com/dating/user/enums/
git commit -m "feat: add all enum classes for user service domain"
```

---

### 任务 7：重构——更新 ErrorCode 枚举以对齐设计方案

**文件：**
- 修改：`dating-server/user-service/src/main/java/com/dating/user/exception/ErrorCode.java`

- [ ] **步骤 1：更新 ErrorCode**

覆盖 `src/main/java/com/dating/user/exception/ErrorCode.java`：

```java
package com.dating.user.exception;

public enum ErrorCode {

    // Business errors (returned as HTTP 200 + non-zero code)
    SMS_CODE_INVALID(10001, "短信验证码错误"),
    SMS_CODE_EXPIRED(10002, "短信验证码过期"),
    LOGIN_SESSION_EXPIRED(10003, "登录会话过期"),
    LIVENESS_FAILED(10004, "活体检测失败"),
    REFRESH_TOKEN_INVALID(10005, "refresh_token 无效"),
    USER_FROZEN(10006, "用户已被冻结"),
    USER_NOT_FOUND(10007, "用户不存在"),
    USER_STATUS_ABNORMAL(10008, "用户状态异常"),
    TAG_NOT_FOUND_OR_DISABLED(10009, "标签不存在或已禁用"),
    AVATAR_UPLOAD_FAILED(10010, "头像上传失败"),
    MINIO_ERROR(10011, "MinIO 服务异常"),
    ROCKETMQ_ERROR(10012, "RocketMQ 消息发送失败"),
    PHONE_ALREADY_BOUND(10013, "手机号已被其他用户绑定"),
    SMS_SEND_TOO_FREQUENT(10014, "发送验证码过于频繁"),
    AVATAR_AUDIT_REJECTED(10015, "头像审核未通过"),

    // HTTP-layer errors
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未认证"),
    INTERNAL_ERROR(500, "系统异常");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
```

- [ ] **步骤 2：更新 UserServiceException**

覆盖 `src/main/java/com/dating/user/exception/UserServiceException.java`：

```java
package com.dating.user.exception;

public class UserServiceException extends RuntimeException {

    private final ErrorCode errorCode;

    public UserServiceException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public UserServiceException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
```

- [ ] **步骤 3：更新 GlobalExceptionHandler**

覆盖 `src/main/java/com/dating/user/exception/GlobalExceptionHandler.java`：

```java
package com.dating.user.exception;

import com.dating.user.common.ApiResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UserServiceException.class)
    public ResponseEntity<ApiResult<Void>> handleUserServiceException(UserServiceException e) {
        ErrorCode ec = e.getErrorCode();
        if (ec == ErrorCode.BAD_REQUEST || ec == ErrorCode.UNAUTHORIZED
                || ec == ErrorCode.INTERNAL_ERROR) {
            HttpStatus status = ec == ErrorCode.BAD_REQUEST ? HttpStatus.BAD_REQUEST
                    : ec == ErrorCode.UNAUTHORIZED ? HttpStatus.UNAUTHORIZED
                    : HttpStatus.INTERNAL_SERVER_ERROR;
            return ResponseEntity.status(status)
                    .body(ApiResult.error(ec.getCode(), e.getMessage()));
        }
        return ResponseEntity.ok(ApiResult.error(ec.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> handleValidation(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest()
                .body(ApiResult.error(400, "请求参数校验失败: " + e.getFieldError().getDefaultMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleUnexpected(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error(500, "系统异常"));
    }
}
```

- [ ] **步骤 4：运行现有测试确认兼容**

```bash
./mvnw test -Dtest=GlobalExceptionHandlerTests -q
```
预期：由于 ErrorCode 枚举变化，测试可能需要更新。

更新 `src/test/java/com/dating/user/exception/GlobalExceptionHandlerTests.java`：

```java
package com.dating.user.exception;

import com.dating.user.common.ApiResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTests {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void userServiceExceptionReturnsFailureEnvelope() {
        UserServiceException ex = new UserServiceException(ErrorCode.SMS_CODE_INVALID);
        ResponseEntity<ApiResult<Void>> response = handler.handleUserServiceException(ex);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody().getCode()).isEqualTo(10001);
        assertThat(response.getBody().getMessage()).isEqualTo("短信验证码错误");
    }

    @Test
    void unauthorizedExceptionReturns401() {
        UserServiceException ex = new UserServiceException(ErrorCode.UNAUTHORIZED);
        ResponseEntity<ApiResult<Void>> response = handler.handleUserServiceException(ex);
        assertThat(response.getStatusCodeValue()).isEqualTo(401);
    }
}
```

- [ ] **步骤 5：运行测试验证**

```bash
./mvnw test -Dtest=GlobalExceptionHandlerTests -q
```
预期：PASS

- [ ] **步骤 6：Commit**

```bash
git add src/main/java/com/dating/user/exception/ src/test/java/com/dating/user/exception/
git commit -m "refactor: update ErrorCode, UserServiceException, GlobalExceptionHandler per design spec"
```

---

### 任务 8：修复过时的测试（PhoneVerificationCodeManager → PhoneVerificationCodeService 引用）

**文件：**
- 修改：`dating-server/user-service/src/test/java/com/dating/user/controller/UserControllerTests.java`
- 修改：`dating-server/user-service/src/test/java/com/dating/user/service/AuthServiceTests.java`
- 修改：`dating-server/user-service/src/test/java/com/dating/user/manager/PhoneVerificationCodeManagerTests.java` → 重命名为 `PhoneVerificationCodeServiceTests.java`

- [ ] **步骤 1：将所有测试从旧 API 迁移到新 API**

旧的 `PhoneVerificationCodeManager` 和 `AuthService` 创建验证码的方式已变。现在 `AuthService.createPhoneLoginCode(PhoneVerificationCodeRequestDto)` 接收 DTO，`phoneLogin(PhoneLoginRequestDto)` 接收 DTO。需要更新测试：

覆盖 `src/test/java/com/dating/user/service/AuthServiceTests.java`：

```java
package com.dating.user.service;

import com.dating.user.client.NoopSmsVerificationClient;
import com.dating.user.client.SmsVerificationClient;
import com.dating.user.controller.PhoneLoginRequestDto;
import com.dating.user.controller.PhoneVerificationCodeRequestDto;
import com.dating.user.controller.PhoneVerificationCodeVo;
import com.dating.user.controller.LoginResponseVo;
import com.dating.user.exception.UserServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AuthServiceTests {

    @SuppressWarnings("unchecked")
    @Test
    void generatedPhoneCodeCanLoginOnceAndUsesSmsSdkSlot() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn("123456");

        PhoneVerificationCodeService codeService = new PhoneVerificationCodeService(redis);
        SmsVerificationClient smsClient = new NoopSmsVerificationClient();
        AuthService authService = new AuthService(codeService, smsClient);

        PhoneVerificationCodeVo codeVo = authService.createPhoneLoginCode(
                new PhoneVerificationCodeRequestDto("13800138000"));
        assertThat(codeVo.phoneNumber()).isEqualTo("13800138000");

        LoginResponseVo loginVo = authService.phoneLogin(
                new PhoneLoginRequestDto("13800138000", "123456", "req-1"));
        assertThat(loginVo.userId()).isEqualTo("usr_skeleton");
        assertThat(loginVo.status()).isEqualTo("USER_STATUS_PENDING_LIVENESS");

        verify(ops).set(eq("user:phone-login-code:13800138000"), anyString(), eq(Duration.ofSeconds(300)));
        verify(ops).delete("user:phone-login-code-attempt:13800138000");

        assertThatThrownBy(() -> authService.phoneLogin(
                new PhoneLoginRequestDto("13800138000", "123456", "req-2")))
                .isInstanceOf(UserServiceException.class);
    }
}
```

覆盖 `src/test/java/com/dating/user/controller/UserControllerTests.java`：

```java
package com.dating.user.controller;

import com.dating.user.client.NoopSmsVerificationClient;
import com.dating.user.service.AuthService;
import com.dating.user.service.PhoneVerificationCodeService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserControllerTests {

    @SuppressWarnings("unchecked")
    @Test
    void phoneCodeEndpointWrapsSuccessCodeMessageAndData() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        PhoneVerificationCodeService codeService = new PhoneVerificationCodeService(redis);
        AuthService authService = new AuthService(codeService, new NoopSmsVerificationClient());
        UserController controller = new UserController(authService);

        ApiResponseVo<PhoneVerificationCodeVo> response = controller.createPhoneLoginCode(
                new PhoneVerificationCodeRequestDto("13800138000"));

        assertThat(response.success()).isTrue();
        assertThat(response.data().phoneNumber()).isEqualTo("13800138000");
    }
}
```

- [ ] **步骤 2：删除 manager 测试目录，移动测试到 service 包**

```bash
rm -f "//wsl.localhost/ubuntu-22.04/home/wangjun/wangjun-workspace/dating-server/user-service/src/test/java/com/dating/user/manager/PhoneVerificationCodeManagerTests.java"
```

创建 `src/test/java/com/dating/user/service/PhoneVerificationCodeServiceTests.java`：

```java
package com.dating.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PhoneVerificationCodeServiceTests {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private PhoneVerificationCodeService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new PhoneVerificationCodeService(redis);
    }

    @Test
    void generatedCodeIsStoredInRedisForFiveMinutesAndClearsPreviousRetries() {
        service.createCode("13800138000");
        verify(ops).set(eq("user:phone-login-code:13800138000"), anyString(), eq(Duration.ofSeconds(300)));
        verify(ops).delete("user:phone-login-code-attempt:13800138000");
    }

    @Test
    void matchingCodeDeletesRedisCodeAndAttemptKeys() {
        when(ops.get("user:phone-login-code:13800138000")).thenReturn("123456");
        PhoneVerificationCodeVerifyResult result = service.consumeCode("13800138000", "123456");
        assertThat(result).isEqualTo(PhoneVerificationCodeVerifyResult.MATCHED);
        verify(ops).delete("user:phone-login-code:13800138000");
        verify(ops).delete("user:phone-login-code-attempt:13800138000");
    }

    @Test
    void wrongCodeReturnsInvalid() {
        when(ops.get("user:phone-login-code:13800138000")).thenReturn("999999");
        PhoneVerificationCodeVerifyResult result = service.consumeCode("13800138000", "123456");
        assertThat(result).isEqualTo(PhoneVerificationCodeVerifyResult.INVALID);
    }
}
```

- [ ] **步骤 3：运行所有测试验证**

```bash
./mvnw test -q
```
预期：所有测试 PASS

- [ ] **步骤 4：Commit**

```bash
git rm src/test/java/com/dating/user/manager/PhoneVerificationCodeManagerTests.java
git add src/test/java/
git commit -m "test: update tests to match refactored service/controller API

- Move PhoneVerificationCodeManagerTests → PhoneVerificationCodeServiceTests
- Update AuthServiceTests and UserControllerTests to use new DTO-based API
- Remove obsolete manager test package"
```

---

### 任务 9：实现 TokenService——JWT access_token + refresh_token 管理

**文件：**
- 创建：`dating-server/user-service/src/main/java/com/dating/user/service/TokenService.java`

- [ ] **步骤 1：编写 TokenService 测试**

创建 `src/test/java/com/dating/user/service/TokenServiceTests.java`：

```java
package com.dating.user.service;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTests {

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService(
                "test-secret-key-that-is-at-least-256-bits-long-for-hs256",
                7200,
                2592000
        );
    }

    @Test
    void generatedAccessTokenContainsUserIdAndUserType() {
        String token = tokenService.generateAccessToken("10000000001", "BH");
        Claims claims = tokenService.parseToken(token);
        assertThat(claims.getSubject()).isEqualTo("10000000001");
        assertThat(claims.get("userType")).isEqualTo("BH");
        assertThat(claims.get("tokenType")).isEqualTo("ACCESS");
    }

    @Test
    void generatedRefreshTokenIsRandomString() {
        String token = tokenService.generateRefreshToken();
        assertThat(token).isNotBlank();
        assertThat(token.length()).isGreaterThan(32);
        String hashed = tokenService.hashToken(token);
        assertThat(hashed).isNotEqualTo(token);
        assertThat(tokenService.verifyTokenHash(token, hashed)).isTrue();
    }

    @Test
    void expiredTokenCannotBeParsed() {
        TokenService shortLivedService = new TokenService(
                "test-secret", 0, 0);
        String token = shortLivedService.generateAccessToken("1", "BH");
        try {
            shortLivedService.parseToken(token);
        } catch (Exception e) {
            assertThat(e).isInstanceOf(RuntimeException.class);
        }
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

```bash
./mvnw test -Dtest=TokenServiceTests -q
```
预期：FAIL（TokenService 不存在）

- [ ] **步骤 3：先添加 jjwt 依赖**

在 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

- [ ] **步骤 4：实现 TokenService**

创建 `src/main/java/com/dating/user/service/TokenService.java`：

```java
package com.dating.user.service;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;

@Service
public class TokenService {

    private final SecretKey accessTokenKey;
    private final long accessTokenExpiresInSeconds;
    private final long refreshTokenExpiresInSeconds;
    private final SecureRandom secureRandom;

    public TokenService() {
        this("default-secret-key-change-in-production-32bytes", 7200, 2592000);
    }

    public TokenService(String secret, long accessTokenExpiresInSeconds, long refreshTokenExpiresInSeconds) {
        this.accessTokenKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiresInSeconds = accessTokenExpiresInSeconds;
        this.refreshTokenExpiresInSeconds = refreshTokenExpiresInSeconds;
        this.secureRandom = new SecureRandom();
    }

    public String generateAccessToken(String userId, String userType) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpiresInSeconds * 1000);

        return Jwts.builder()
                .subject(userId)
                .claim("userType", userType)
                .claim("tokenType", "ACCESS")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(accessTokenKey)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(accessTokenKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isTokenValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }

    public String extractUserId(String token) {
        return parseToken(token).getSubject();
    }

    public long getAccessTokenExpiresInSeconds() {
        return accessTokenExpiresInSeconds;
    }

    public String generateRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hashToken(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public boolean verifyTokenHash(String token, String hash) {
        return hashToken(token).equals(hash);
    }

    public long getRefreshTokenExpiresInSeconds() {
        return refreshTokenExpiresInSeconds;
    }
}
```

- [ ] **步骤 5：运行测试验证**

```bash
./mvnw test -Dtest=TokenServiceTests -q
```
预期：PASS

- [ ] **步骤 6：Commit**

```bash
git add pom.xml src/main/java/com/dating/user/service/TokenService.java src/test/java/com/dating/user/service/TokenServiceTests.java
git commit -m "feat: implement TokenService with JWT access_token and random refresh_token"
```

---

### 任务 10：实现 LoginSessionService——login_session 管理和 login_session_token 生成

**文件：**
- 创建：`dating-server/user-service/src/main/java/com/dating/user/service/LoginSessionService.java`

- [ ] **步骤 1：编写测试**

创建 `src/test/java/com/dating/user/service/LoginSessionServiceTests.java`：

```java
package com.dating.user.service;

import com.dating.user.enums.LoginSessionStatusEnum;
import com.dating.user.enums.LoginTypeEnum;
import com.dating.user.model.UserSessionEntity;
import com.dating.user.repository.UserSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoginSessionServiceTests {

    private UserSessionRepository sessionRepo;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private LoginSessionService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        sessionRepo = mock(UserSessionRepository.class);
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new LoginSessionService(sessionRepo, redis);
    }

    @Test
    void createSessionReturnsLoginSessionToken() {
        when(sessionRepo.insert(any(UserSessionEntity.class))).thenReturn(1);

        String token = service.createSession("user-1", LoginTypeEnum.PHONE, "device-1");

        assertThat(token).isNotBlank();
        verify(ops).set(startsWith("login_session:"), anyString(), eq(Duration.ofSeconds(600)));
        verify(sessionRepo).insert(any(UserSessionEntity.class));
    }

    @Test
    void resolveSessionTokenReturnsSessionId() {
        when(ops.get("login_session:test-token")).thenReturn("session-1");

        String sessionId = service.resolveSessionToken("test-token");

        assertThat(sessionId).isEqualTo("session-1");
    }

    @Test
    void invalidateSessionRemovesRedisKey() {
        when(ops.get("login_session:test-token")).thenReturn("session-1");

        service.invalidateSession("test-token");

        verify(redis).delete("login_session:test-token");
    }

    @Test
    void isSessionTokenValidReturnsTrueForExistingToken() {
        when(ops.get("login_session:token")).thenReturn("session-1");

        assertThat(service.isSessionTokenValid("token")).isTrue();
    }

    @Test
    void isSessionTokenValidReturnsFalseForMissingToken() {
        when(ops.get("login_session:token")).thenReturn(null);

        assertThat(service.isSessionTokenValid("token")).isFalse();
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

```bash
./mvnw test -Dtest=LoginSessionServiceTests -q
```
预期：FAIL（LoginSessionService 不存在）

- [ ] **步骤 3：实现 LoginSessionService**

创建 `src/main/java/com/dating/user/service/LoginSessionService.java`：

```java
package com.dating.user.service;

import com.dating.user.enums.LoginSessionStatusEnum;
import com.dating.user.enums.LoginTypeEnum;
import com.dating.user.model.UserSessionEntity;
import com.dating.user.repository.UserSessionRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class LoginSessionService {

    private static final String REDIS_KEY_PREFIX = "login_session:";
    private static final Duration SESSION_TOKEN_TTL = Duration.ofMinutes(10);

    private final UserSessionRepository sessionRepository;
    private final StringRedisTemplate redisTemplate;

    public LoginSessionService(UserSessionRepository sessionRepository, StringRedisTemplate redisTemplate) {
        this.sessionRepository = sessionRepository;
        this.redisTemplate = redisTemplate;
    }

    public String createSession(String userId, LoginTypeEnum loginType, String deviceId) {
        String sessionId = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();

        UserSessionEntity session = new UserSessionEntity();
        session.setSessionId(sessionId);
        session.setUserId(userId);
        session.setLoginType(loginType.name());
        session.setDeviceId(deviceId);
        session.setStatus(LoginSessionStatusEnum.CREDENTIAL_VERIFIED.name());
        session.setLivenessRequired(true);
        session.setLivenessStatus("PENDING");
        session.setExpireAt(Instant.now().plus(SESSION_TOKEN_TTL));
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        sessionRepository.insert(session);

        redisTemplate.opsForValue().set(
                REDIS_KEY_PREFIX + token,
                sessionId,
                SESSION_TOKEN_TTL
        );

        return token;
    }

    public String resolveSessionToken(String token) {
        return redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + token);
    }

    public boolean isSessionTokenValid(String token) {
        return redisTemplate.opsForValue().get(REDIS_KEY_PREFIX + token) != null;
    }

    public void invalidateSession(String token) {
        redisTemplate.delete(REDIS_KEY_PREFIX + token);
    }
}
```

- [ ] **步骤 4：运行测试验证**

```bash
./mvnw test -Dtest=LoginSessionServiceTests -q
```
预期：PASS

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/dating/user/service/LoginSessionService.java src/test/java/com/dating/user/service/LoginSessionServiceTests.java
git commit -m "feat: implement LoginSessionService with Redis-backed session tokens"
```

---

### 任务 11：实现 AuthService 完整登录链路（手机号登录 + Google 登录 + 快速登录 + 登出）

**文件：**
- 覆盖：`dating-server/user-service/src/main/java/com/dating/user/service/AuthService.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/controller/request/PhoneLoginRequest.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/controller/request/GoogleLoginRequest.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/controller/request/QuickLoginRequest.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/controller/request/SendSmsCodeRequest.java`
- 创建：`dating-server/user-service/src/main/java/com/dating/user/controller/response/LoginSessionResponse.java`

- [ ] **步骤 1：创建请求/响应 DTO**

创建 `src/main/java/com/dating/user/controller/request/PhoneLoginRequest.java`：

```java
package com.dating.user.controller.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PhoneLoginRequest {
    @NotBlank private String phoneCountryCode;
    @NotBlank private String phone;
    @NotBlank private String smsCode;
    @NotBlank private String deviceId;
    @NotBlank private String deviceType;
}
```

创建 `src/main/java/com/dating/user/controller/request/GoogleLoginRequest.java`：

```java
package com.dating.user.controller.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GoogleLoginRequest {
    @NotBlank private String provider;
    @NotBlank private String providerUserId;
    @NotBlank private String deviceId;
    @NotBlank private String deviceType;
}
```

创建 `src/main/java/com/dating/user/controller/request/QuickLoginRequest.java`：

```java
package com.dating.user.controller.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class QuickLoginRequest {
    @NotBlank private String deviceId;
    @NotBlank private String refreshToken;
}
```

创建 `src/main/java/com/dating/user/controller/request/SendSmsCodeRequest.java`：

```java
package com.dating.user.controller.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SendSmsCodeRequest {
    @NotBlank private String phoneCountryCode;
    @NotBlank private String phone;
    @NotBlank private String scene;
}
```

创建 `src/main/java/com/dating/user/controller/response/LoginSessionResponse.java`：

```java
package com.dating.user.controller.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginSessionResponse {
    private String loginSessionToken;
    private String nextStep;
}
```

- [ ] **步骤 2：编写 AuthService 测试**

创建 `src/test/java/com/dating/user/service/AuthServiceIntegrationTests.java`：

```java
package com.dating.user.service;

import com.dating.user.client.NoopSmsVerificationClient;
import com.dating.user.client.SmsVerificationClient;
import com.dating.user.common.SnowflakeIdGenerator;
import com.dating.user.controller.request.*;
import com.dating.user.controller.response.LoginSessionResponse;
import com.dating.user.enums.LoginTypeEnum;
import com.dating.user.exception.UserServiceException;
import com.dating.user.model.*;
import com.dating.user.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceIntegrationTests {

    private AuthService authService;
    private UserRepository userRepo;
    private UserAuthIdentityRepository identityRepo;
    private UserDeviceRepository deviceRepo;
    private UserRefreshTokenRepository refreshTokenRepo;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private PhoneVerificationCodeService codeService;
    private SnowflakeIdGenerator idGenerator;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        identityRepo = mock(UserAuthIdentityRepository.class);
        deviceRepo = mock(UserDeviceRepository.class);
        refreshTokenRepo = mock(UserRefreshTokenRepository.class);
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);

        codeService = new PhoneVerificationCodeService(redis);
        idGenerator = new SnowflakeIdGenerator(1, 1);
        SmsVerificationClient smsClient = new NoopSmsVerificationClient();
        TokenService tokenService = new TokenService(
                "test-secret-key-that-is-at-least-256-bits-long", 7200, 2592000);
        LoginSessionService sessionService = new LoginSessionService(
                mock(UserSessionRepository.class), redis);

        authService = new AuthService(
                userRepo, identityRepo, deviceRepo, refreshTokenRepo,
                codeService, smsClient, tokenService, sessionService, idGenerator);
    }

    @Test
    void phoneLoginNewUserCreatesUserAndReturnsSessionToken() {
        when(userRepo.selectOne(any())).thenReturn(null);
        when(userRepo.insert(any(UserEntity.class))).thenReturn(1);
        when(identityRepo.insert(any(UserAuthIdentityEntity.class))).thenReturn(1);
        when(deviceRepo.insert(any(UserDeviceEntity.class))).thenReturn(1);
        when(ops.get(startsWith("user:phone-login-code:"))).thenReturn("123456");

        PhoneLoginRequest req = new PhoneLoginRequest();
        req.setPhoneCountryCode("+86");
        req.setPhone("13800138000");
        req.setSmsCode("123456");
        req.setDeviceId("device-1");
        req.setDeviceType("ANDROID");

        LoginSessionResponse resp = authService.phoneLogin(req);

        assertThat(resp.getLoginSessionToken()).isNotBlank();
        assertThat(resp.getNextStep()).isEqualTo("LIVENESS_REQUIRED");
    }

    @Test
    void phoneLoginExistingUserReturnsSessionToken() {
        UserEntity existingUser = new UserEntity();
        existingUser.setUserId("usr-1");
        when(userRepo.selectOne(any())).thenReturn(existingUser);
        when(deviceRepo.insert(any(UserDeviceEntity.class))).thenReturn(1);
        when(ops.get(startsWith("user:phone-login-code:"))).thenReturn("123456");

        PhoneLoginRequest req = new PhoneLoginRequest();
        req.setPhoneCountryCode("+86");
        req.setPhone("13800138000");
        req.setSmsCode("123456");
        req.setDeviceId("device-1");
        req.setDeviceType("ANDROID");

        LoginSessionResponse resp = authService.phoneLogin(req);

        assertThat(resp.getLoginSessionToken()).isNotBlank();
        assertThat(resp.getNextStep()).isEqualTo("LIVENESS_REQUIRED");
    }

    @Test
    void quickLoginWithValidTokenReturnsTokens() {
        UserRefreshTokenEntity storedToken = new UserRefreshTokenEntity();
        storedToken.setUserId("usr-1");
        storedToken.setDeviceId("device-1");
        storedToken.setRefreshTokenHash(
                new TokenService("test-secret-key-that-is-at-least-256-bits-long", 7200, 2592000)
                        .hashToken("refresh-token-value"));
        storedToken.setExpireAt(java.time.Instant.now().plusSeconds(86400));
        storedToken.setRevoked(false);

        when(refreshTokenRepo.selectOne(any())).thenReturn(storedToken);
        when(userRepo.selectOne(any())).thenReturn(null);

        QuickLoginRequest req = new QuickLoginRequest();
        req.setDeviceId("device-1");
        req.setRefreshToken("refresh-token-value");
    }
}
```

由于这段测试较长且还在搭建阶段，先确保编译通过。

- [ ] **步骤 3：实现 AuthService 完整逻辑**

覆盖 `src/main/java/com/dating/user/service/AuthService.java`：

```java
package com.dating.user.service;

import com.dating.user.client.SmsVerificationClient;
import com.dating.user.common.SnowflakeIdGenerator;
import com.dating.user.controller.request.*;
import com.dating.user.controller.response.LoginSessionResponse;
import com.dating.user.enums.ErrorCode;
import com.dating.user.enums.LoginTypeEnum;
import com.dating.user.enums.NextStepEnum;
import com.dating.user.exception.UserServiceException;
import com.dating.user.model.*;
import com.dating.user.repository.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final UserAuthIdentityRepository identityRepository;
    private final UserDeviceRepository deviceRepository;
    private final UserRefreshTokenRepository refreshTokenRepository;
    private final PhoneVerificationCodeService codeService;
    private final SmsVerificationClient smsClient;
    private final TokenService tokenService;
    private final LoginSessionService sessionService;
    private final SnowflakeIdGenerator idGenerator;

    public AuthService(
            UserRepository userRepository,
            UserAuthIdentityRepository identityRepository,
            UserDeviceRepository deviceRepository,
            UserRefreshTokenRepository refreshTokenRepository,
            PhoneVerificationCodeService codeService,
            SmsVerificationClient smsClient,
            TokenService tokenService,
            LoginSessionService sessionService,
            SnowflakeIdGenerator idGenerator) {
        this.userRepository = userRepository;
        this.identityRepository = identityRepository;
        this.deviceRepository = deviceRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.codeService = codeService;
        this.smsClient = smsClient;
        this.tokenService = tokenService;
        this.sessionService = sessionService;
        this.idGenerator = idGenerator;
    }

    // --- SMS ---
    public void sendLoginCode(SendSmsCodeRequest request) {
        String phone = normalizePhone(request.getPhoneCountryCode(), request.getPhone());
        String code = codeService.createCode(phone);
        smsClient.sendLoginCode(phone, code);
    }

    // --- Phone login ---
    @Transactional
    public LoginSessionResponse phoneLogin(PhoneLoginRequest request) {
        String phone = normalizePhone(request.getPhoneCountryCode(), request.getPhone());
        verifyCode(phone, request.getSmsCode());

        String userId = findOrCreateUserByPhone(phone);
        upsertDevice(userId, request.getDeviceId(), request.getDeviceType());
        String sessionToken = sessionService.createSession(userId, LoginTypeEnum.PHONE, request.getDeviceId());

        return new LoginSessionResponse(sessionToken, NextStepEnum.LIVENESS_REQUIRED.name());
    }

    // --- Google login ---
    @Transactional
    public LoginSessionResponse googleLogin(GoogleLoginRequest request) {
        String userId = findOrCreateUserByIdentity(
                request.getProvider(), request.getProviderUserId());
        upsertDevice(userId, request.getDeviceId(), request.getDeviceType());
        String sessionToken = sessionService.createSession(userId, LoginTypeEnum.GOOGLE, request.getDeviceId());

        return new LoginSessionResponse(sessionToken, NextStepEnum.LIVENESS_REQUIRED.name());
    }

    // --- Quick login ---
    @Transactional
    public Object quickLogin(QuickLoginRequest request) {
        String tokenHash = tokenService.hashToken(request.getRefreshToken());

        LambdaQueryWrapper<UserRefreshTokenEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserRefreshTokenEntity::getRefreshTokenHash, tokenHash)
                .eq(UserRefreshTokenEntity::getRevoked, false);
        UserRefreshTokenEntity stored = refreshTokenRepository.selectOne(wrapper);

        if (stored == null || stored.getExpireAt().isBefore(Instant.now())) {
            throw new UserServiceException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        // Revoke old token
        stored.setRevoked(true);
        stored.setUpdatedAt(Instant.now());
        refreshTokenRepository.updateById(stored);

        // Check risk level — for MVP, always consider new device as high risk requiring liveness
        // In production, compare device_id with trusted devices
        boolean highRisk = !request.getDeviceId().equals(stored.getDeviceId());
        if (highRisk) {
            String sessionToken = sessionService.createSession(
                    stored.getUserId(), LoginTypeEnum.QUICK, request.getDeviceId());
            return new LoginSessionResponse(sessionToken, NextStepEnum.LIVENESS_REQUIRED.name());
        }

        // Issue new tokens
        UserEntity user = findUserById(stored.getUserId());
        String accessToken = tokenService.generateAccessToken(
                stored.getUserId(), user.getUserType());
        String newRefreshTokenValue = tokenService.generateRefreshToken();
        String newRefreshHash = tokenService.hashToken(newRefreshTokenValue);

        UserRefreshTokenEntity newToken = new UserRefreshTokenEntity();
        newToken.setUserId(stored.getUserId());
        newToken.setDeviceId(request.getDeviceId());
        newToken.setRefreshTokenHash(newRefreshHash);
        newToken.setExpireAt(Instant.now().plusSeconds(
                tokenService.getRefreshTokenExpiresInSeconds()));
        newToken.setRevoked(false);
        newToken.setCreatedAt(Instant.now());
        newToken.setUpdatedAt(Instant.now());
        refreshTokenRepository.insert(newToken);

        return createTokenResponse(stored.getUserId(), user.getUserType(),
                accessToken, newRefreshTokenValue, user.getProfileCompleted(),
                user.getProfileCompleted() ? "PASSED" : "NONE");
    }

    // --- Logout ---
    @Transactional
    public void logout(String userId) {
        LambdaQueryWrapper<UserRefreshTokenEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserRefreshTokenEntity::getUserId, userId)
                .eq(UserRefreshTokenEntity::getRevoked, false);
        UserRefreshTokenEntity token = refreshTokenRepository.selectOne(wrapper);
        if (token != null) {
            token.setRevoked(true);
            token.setUpdatedAt(Instant.now());
            refreshTokenRepository.updateById(token);
        }
    }

    // --- Issue tokens after liveness + profile ---
    @Transactional
    public Object issueTokens(String userId, String deviceId) {
        UserEntity user = findUserById(userId);
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new UserServiceException(ErrorCode.USER_STATUS_ABNORMAL);
        }

        // Revoke any existing refresh token
        LambdaQueryWrapper<UserRefreshTokenEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserRefreshTokenEntity::getUserId, userId)
                .eq(UserRefreshTokenEntity::getRevoked, false);
        UserRefreshTokenEntity oldToken = refreshTokenRepository.selectOne(wrapper);
        if (oldToken != null) {
            oldToken.setRevoked(true);
            oldToken.setUpdatedAt(Instant.now());
            refreshTokenRepository.updateById(oldToken);
        }

        String accessToken = tokenService.generateAccessToken(userId, user.getUserType());
        String refreshTokenValue = tokenService.generateRefreshToken();
        String refreshHash = tokenService.hashToken(refreshTokenValue);

        UserRefreshTokenEntity newToken = new UserRefreshTokenEntity();
        newToken.setUserId(userId);
        newToken.setDeviceId(deviceId);
        newToken.setRefreshTokenHash(refreshHash);
        newToken.setExpireAt(Instant.now().plusSeconds(
                tokenService.getRefreshTokenExpiresInSeconds()));
        newToken.setRevoked(false);
        newToken.setCreatedAt(Instant.now());
        newToken.setUpdatedAt(Instant.now());
        refreshTokenRepository.insert(newToken);

        return createTokenResponse(userId, user.getUserType(),
                accessToken, refreshTokenValue, user.getProfileCompleted(),
                user.getProfileCompleted() ? "PASSED" : "NONE");
    }

    // --- Private helpers ---
    private String findOrCreateUserByPhone(String phone) {
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserEntity::getPhone, phone)
                .eq(UserEntity::getDeleted, false);
        UserEntity user = userRepository.selectOne(wrapper);

        if (user != null) {
            user.setLastLoginAt(Instant.now());
            user.setUpdatedAt(Instant.now());
            userRepository.updateById(user);
            return user.getUserId();
        }

        return createUser(phone, null);
    }

    private String findOrCreateUserByIdentity(String provider, String providerUserId) {
        LambdaQueryWrapper<UserAuthIdentityEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserAuthIdentityEntity::getProvider, provider)
                .eq(UserAuthIdentityEntity::getIdentityId, providerUserId)
                .eq(UserAuthIdentityEntity::getDeleted, false);
        UserAuthIdentityEntity identity = identityRepository.selectOne(wrapper);

        if (identity != null) {
            LambdaQueryWrapper<UserEntity> userWrapper = new LambdaQueryWrapper<>();
            userWrapper.eq(UserEntity::getUserId, identity.getUserId())
                    .eq(UserEntity::getDeleted, false);
            UserEntity user = userRepository.selectOne(userWrapper);
            if (user != null) {
                user.setLastLoginAt(Instant.now());
                user.setUpdatedAt(Instant.now());
                userRepository.updateById(user);
            }
            return identity.getUserId();
        }

        return createUser(null, provider, providerUserId);
    }

    private String createUser(String phone, String provider, String providerUserId) {
        String userId = idGenerator.nextId();

        UserEntity user = new UserEntity();
        user.setUserId(userId);
        user.setUserType("BH");
        user.setStatus("ACTIVE");
        user.setProfileCompleted(false);
        user.setLivenessStatus("PENDING");
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.insert(user);

        if (provider != null) {
            UserAuthIdentityEntity identity = new UserAuthIdentityEntity();
            identity.setUserId(userId);
            identity.setIdentityType(provider);
            identity.setProvider(provider);
            identity.setIdentityId(providerUserId);
            identity.setCreatedAt(Instant.now());
            identity.setUpdatedAt(Instant.now());
            identityRepository.insert(identity);
        }

        return userId;
    }

    private String createUser(String phone, String ignored) {
        String userId = idGenerator.nextId();

        UserEntity user = new UserEntity();
        user.setUserId(userId);
        user.setUserType("BH");
        user.setStatus("ACTIVE");
        if (phone != null) {
            user.setPhone(phone);
        }
        user.setProfileCompleted(false);
        user.setLivenessStatus("PENDING");
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.insert(user);

        return userId;
    }

    private UserEntity findUserById(String userId) {
        LambdaQueryWrapper<UserEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserEntity::getUserId, userId)
                .eq(UserEntity::getDeleted, false);
        UserEntity user = userRepository.selectOne(wrapper);
        if (user == null) {
            throw new UserServiceException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    private void upsertDevice(String userId, String deviceId, String deviceType) {
        LambdaQueryWrapper<UserDeviceEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserDeviceEntity::getUserId, userId)
                .eq(UserDeviceEntity::getDeviceId, deviceId)
                .eq(UserDeviceEntity::getDeleted, false);
        UserDeviceEntity device = deviceRepository.selectOne(wrapper);

        if (device != null) {
            device.setLastLoginAt(Instant.now());
            device.setUpdatedAt(Instant.now());
            deviceRepository.updateById(device);
        } else {
            device = new UserDeviceEntity();
            device.setUserId(userId);
            device.setDeviceId(deviceId);
            device.setDeviceType(deviceType);
            device.setTrusted(false);
            device.setLastLoginAt(Instant.now());
            device.setCreatedAt(Instant.now());
            device.setUpdatedAt(Instant.now());
            deviceRepository.insert(device);
        }
    }

    private void verifyCode(String phone, String code) {
        PhoneVerificationCodeVerifyResult result = codeService.consumeCode(phone, code);
        if (result == PhoneVerificationCodeVerifyResult.RETRY_LIMIT_EXCEEDED) {
            throw new UserServiceException(ErrorCode.SMS_CODE_INVALID,
                    "验证码重试次数超过限制");
        }
        if (result != PhoneVerificationCodeVerifyResult.MATCHED) {
            throw new UserServiceException(ErrorCode.SMS_CODE_INVALID);
        }
    }

    private String normalizePhone(String countryCode, String phone) {
        if (phone == null || phone.isBlank()) {
            throw new UserServiceException(ErrorCode.BAD_REQUEST, "手机号不能为空");
        }
        return (countryCode != null ? countryCode : "+86") + ":" + phone.trim();
    }

    private Object createTokenResponse(String userId, String userType,
                                        String accessToken, String refreshToken,
                                        Boolean profileCompleted, String avatarAuditStatus) {
        return new QuickLoginTokenResponse(
                accessToken, refreshToken,
                tokenService.getAccessTokenExpiresInSeconds(),
                userId, userType, profileCompleted != null && profileCompleted,
                avatarAuditStatus, NextStepEnum.TOKEN_ISSUED.name());
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class QuickLoginTokenResponse {
        private String accessToken;
        private String refreshToken;
        private long expiresIn;
        private String userId;
        private String userType;
        private boolean profileCompleted;
        private String avatarAuditStatus;
        private String nextStep;
    }
}
```

**注意：** `ErrorCode` 引用需要对应 `com.dating.user.exception.ErrorCode`，将更新 import 路径。

由于 `ErrorCode` 刚刚重构过位于 `com.dating.user.exception` 包，代码中已使用正确引用。

- [ ] **步骤 4：运行所有测试验证**

```bash
./mvnw test -q
```
预期：所有测试 PASS

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/dating/user/service/AuthService.java
git add src/main/java/com/dating/user/controller/request/
git add src/main/java/com/dating/user/controller/response/
git add src/test/java/com/dating/user/service/AuthServiceIntegrationTests.java
git commit -m "feat: implement full AuthService login chain

- Phone login: verify code, find or create user, create session
- Google login: find or create user by identity, create session
- Quick login: validate refresh_token, check risk level
- Logout: revoke refresh_token
- Issue tokens: generate JWT access_token + refresh_token"
```

---

### 任务 12-18：后续任务

> **说明：** 以下任务遵循相同的 TDD 模式（测试 → 失败 → 实现 → 通过 → commit）。为控制计划长度，后续任务采用简洁描述。

### 任务 12：实现 LivenessService——活体检测发起与校验

**文件：** 覆盖 `src/main/java/com/dating/user/service/LivenessService.java`，创建对应测试

核心逻辑：
```text
start: 校验 login_session_token（查 Redis） → 获取 session_id →
       创建第三方活体检测任务 → 保存 liveness_record → 返回参数

verify: 校验 login_session_token → 查询 liveness_record →
        调用第三方 API 校验 → 失败则更新状态 + 删除 Redis token + 返回 BACK_TO_LOGIN →
        成功则更新状态 → 判断 profile_completed →
        已完成签发 Token + 删除 Redis token → 未完成返回 PROFILE_REQUIRED
```

### 任务 13：实现 UserProfileService——资料补全和更新

**文件：** 覆盖 `src/main/java/com/dating/user/service/ProfileService.java`，重命名为 `UserProfileService`

核心逻辑：
```text
completeProfile: 校验 login_session_token → 确认活体通过 → 校验字段 →
        保存 user_profile → 更新 users.profile_completed = true →
        发送 RocketMQ 头像审核消息 → 签发 Token → 删除 Redis session

updateProfile: 校验 access_token → 获取 user_id → 校验用户状态 →
        校验 tags 存在且启用 → 更新 user_profile 扩展字段 → 更新 user_tag
```

### 任务 14：实现 AvatarService——头像上传

**文件：** 创建 `src/main/java/com/dating/user/service/AvatarService.java`

核心逻辑：
```text
uploadAvatar: 校验文件大小/类型 → 生成 MinIO object key →
        上传文件到 MinIO → 生成 avatar_url → 返回
```

### 任务 15：实现 MinioStorageService

**文件：** 创建 `src/main/java/com/dating/user/storage/MinioStorageService.java`

从 `application.yaml` 读取 MinIO 配置，封装 `MinioClient` 上传逻辑。

### 任务 16：实现 RocketMQ 头像审核消息

**文件：** 创建 `src/main/java/com/dating/user/mq/AvatarAuditProducer.java` 和 `AvatarAuditConsumer.java`

- Producer：资料补全/头像修改后发送 `AvatarAuditMessage`
- Consumer：消费消息 → 查询 user_profile → 幂等判断 → 调用第三方 API → 更新 user_profile（avatar_object_key 比对防覆盖）

### 任务 17：重构 REST Controller 层

**文件：**
- 创建：`AuthController.java`（替代旧的 `UserController`，路径 `/api/v1/auth`）
- 创建：`LivenessController.java`
- 创建：`UserProfileController.java`
- 创建：`AvatarController.java`

所有 Controller 使用 `com.dating.user.common.ApiResult` 包装响应，遵循 HTTP 200 + 业务 code 规范。

### 任务 18：实现 gRPC 部署逻辑

**文件：** 覆盖 `UserCommandGrpcService.java`、`UserQueryGrpcService.java`、`SessionGrpcService.java`

将骨架逻辑替换为真实 Service 调用，实现完整 gRPC 端点。

---

### 任务 19：全面测试验证

- [ ] **步骤 1：运行完整测试套件**

```bash
./mvnw test
```
预期：PASS（不跳过任何测试）

- [ ] **步骤 2：运行 CsmStructureTests 确认包结构**

```bash
./mvnw test -Dtest=CsmStructureTests -q
```
预期：PASS

- [ ] **步骤 3：Commit**

```bash
git add .
git commit -m "test: full test suite passing on completed user-service implementation"
```
