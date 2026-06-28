package site.jianjiange.mobilegateway.config;

import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Google 登录配置。
 */
@Configuration
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "gateway.google")
public class GoogleLoginConfig {

    /**
     * Google OAuth client id，用于校验 ID token audience。
     */
    private String clientId = "";

    /**
     * Google JWKS 地址。
     */
    private String jwksUri = "https://www.googleapis.com/oauth2/v3/certs";

    /**
     * JWKS 本地缓存有效期秒数。
     */
    @Positive
    private long jwksCacheTtlSeconds = 43_200;

    /**
     * Google JWKS HTTP 请求超时时间。
     */
    @Positive
    private long httpTimeoutMs = 2_000;
}
