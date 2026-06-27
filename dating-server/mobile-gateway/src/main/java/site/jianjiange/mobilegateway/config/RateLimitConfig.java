package site.jianjiange.mobilegateway.config;

import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Redis 滑动窗口限流配置。
 */
@Configuration
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "gateway.rate-limit")
public class RateLimitConfig {

    /**
     * 是否启用入口限流。
     */
    private boolean enabled = true;

    /**
     * 限流窗口秒数。
     */
    @Positive
    private long windowSeconds = 60;

    /**
     * 单窗口内最大请求数。
     */
    @Positive
    private long maxRequests = 120;

    /**
     * Redis 限流异常时是否放行请求。
     */
    private boolean failOpen = true;

}
