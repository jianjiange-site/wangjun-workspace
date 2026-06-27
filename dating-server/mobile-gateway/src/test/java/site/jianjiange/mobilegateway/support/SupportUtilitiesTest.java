package site.jianjiange.mobilegateway.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 网关支撑工具的集成级行为测试。
 */
@ActiveProfiles("test")
@SpringBootTest
class SupportUtilitiesTest {

    @Autowired
    private HmacHasher hmacHasher;

    @Autowired
    private RedisKeyFactory redisKeyFactory;

    @Autowired
    private SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * 验证 HMAC 输出稳定、带版本号且不同用途 secret 隔离。
     */
    @Test
    void hmacHashIsStableVersionedAndUsesDifferentSecrets() {
        String phoneHash = hmacHasher.hashPhone("13800000000");
        String samePhoneHash = hmacHasher.hashPhone("13800000000");
        String deviceHash = hmacHasher.hashDevice("13800000000");

        assertThat(phoneHash).startsWith("v1:");
        assertThat(phoneHash).isEqualTo(samePhoneHash);
        assertThat(phoneHash).isNotEqualTo(deviceHash);
    }

    /**
     * 验证 Redis key 工厂始终拼接配置前缀。
     */
    @Test
    void redisKeysAlwaysUseConfiguredPrefix() {
        assertThat(redisKeyFactory.rateLimit("ip", "127.0.0.1"))
                .isEqualTo("dating:mobile-gateway-test:ratelimit:ip:127.0.0.1");
        assertThat(redisKeyFactory.jwtBlacklist("jti-1"))
                .isEqualTo("dating:mobile-gateway-test:jwt:blacklist:jti-1");
    }

    /**
     * 验证雪花 ID 连续生成时为正数且不重复。
     */
    @Test
    void snowflakeIdsAreUniqueAndPositive() {
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            long id = snowflakeIdGenerator.nextId();
            assertThat(id).isPositive();
            assertThat(ids.add(id)).isTrue();
        }
    }
}
