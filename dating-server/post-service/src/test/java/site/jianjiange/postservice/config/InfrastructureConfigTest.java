package site.jianjiange.postservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.minio.MinioClient;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 基础设施配置测试，验证配置属性绑定和核心客户端 Bean 可以创建。
 */
@ActiveProfiles("test")
@SpringBootTest
class InfrastructureConfigTest {

    @Autowired
    private CacheKeyPrefixer cacheKeyPrefixer;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private GrpcConfig grpcConfig;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private ObjectStorageProperties objectStorageProperties;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 验证缓存、数据源、gRPC、对象存储和 Redis 基础配置均能被 Spring 创建。
     */
    @Test
    void infrastructurePropertiesAndClientsAreCreated() {
        assertThat(cacheKeyPrefixer.prefix("post:test")).isEqualTo("wangjun:post:test");
        assertThat(cacheKeyPrefixer.prefix("wangjun:post:test")).isEqualTo("wangjun:post:test");
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getConnectionInitSql()).isEqualTo("SET TIME ZONE 'UTC'");
        assertThat(grpcConfig.profileServiceName()).isEqualTo("wangjun-profile-service");
        assertThat(objectStorageProperties.bucket()).isEqualTo("dating-app");
        assertThat(minioClient).isNotNull();
        assertThat(redisTemplate.getKeySerializer()).isNotNull();
        assertThat(redisTemplate.getValueSerializer()).isNotNull();
    }
}
