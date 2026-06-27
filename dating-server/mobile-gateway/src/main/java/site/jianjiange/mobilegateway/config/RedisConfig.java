package site.jianjiange.mobilegateway.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.validation.annotation.Validated;

/**
 * Redis 业务配置和字符串序列化模板定义。
 */
@Configuration
@Validated
@ConfigurationProperties(prefix = "gateway.redis")
public class RedisConfig {

    /**
     * 网关 Redis key 统一前缀。
     */
    @NotBlank
    @Getter
    @Setter
    private String keyPrefix = "dating:mobile-gateway";

    /**
     * 创建字符串序列化的 RedisTemplate，避免默认 JDK 序列化写入不可读二进制值。
     *
     * @param connectionFactory Redis 连接工厂
     * @return 字符串 key / value RedisTemplate
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        StringRedisSerializer stringSerializer = StringRedisSerializer.UTF_8;
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 创建字符串 Redis 客户端，供限流和 refresh token 缓存直接操作字符串值。
     *
     * @param connectionFactory Redis 连接工厂
     * @return 字符串 Redis 客户端
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

}
