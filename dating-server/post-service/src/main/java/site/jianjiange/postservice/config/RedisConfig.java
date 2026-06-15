package site.jianjiange.postservice.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 基础配置，提供业务 Key 前缀工具和统一序列化的 RedisTemplate。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        CacheProperties.class,
        GrpcConfig.class
})
public class RedisConfig {

    /**
     * 创建 Redis 业务 Key 前缀工具。
     *
     * @param properties 缓存配置属性
     * @return Key 前缀工具
     */
    @Bean
    public CacheKeyPrefixer cacheKeyPrefixer(CacheProperties properties) {
        return new CacheKeyPrefixer(properties.keyPrefix());
    }

    /**
     * 创建面向对象值的 RedisTemplate，Key 使用字符串序列化，Value 使用 JSON 序列化。
     *
     * @param connectionFactory Redis 连接工厂
     * @return 已配置序列化策略的 RedisTemplate
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(redisObjectMapper());
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 创建 Redis JSON 序列化使用的 ObjectMapper。
     *
     * @return 启用类型信息的 ObjectMapper
     */
    private ObjectMapper redisObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        return objectMapper;
    }
}
