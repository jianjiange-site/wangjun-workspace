package site.jianjiange.mobilegateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * 网关自签 JWT 配置。
 */
@Configuration
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "gateway.jwt")
public class JwtConfig {

    /**
     * JWT issuer。
     */
    @NotBlank
    private String issuer = "dating-mobile-gateway";

    /**
     * access token 有效期秒数。
     */
    @Positive
    private long accessTokenTtlSeconds = 900;

    /**
     * refresh token 有效期秒数。
     */
    @Positive
    private long refreshTokenTtlSeconds = 2_592_000;

    /**
     * RS256 私钥 PEM 内容。
     */
    @NotBlank
    private String privateKey;

    /**
     * RS256 公钥 PEM 内容。
     */
    @NotBlank
    private String publicKey;

}
