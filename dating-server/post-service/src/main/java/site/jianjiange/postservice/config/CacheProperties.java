package site.jianjiange.postservice.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 缓存相关配置属性，当前用于统一 Redis 业务 Key 前缀。
 *
 * @param keyPrefix Redis 业务 Key 前缀
 */
@Validated
@ConfigurationProperties(prefix = "dating.cache")
public record CacheProperties(
        @NotBlank String keyPrefix
) {
}
