package site.jianjiange.mobilegateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * HMAC hash 配置，按用途隔离不同 secret。
 */
@Configuration
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "gateway.hash")
public class HashConfig {

    /**
     * HMAC hash 版本号，用于后续密钥滚动和兼容。
     */
    @Positive
    private int version = 1;

    /**
     * 手机号 HMAC secret。
     */
    @NotBlank
    private String phoneSecret;

    /**
     * Google subject HMAC secret。
     */
    @NotBlank
    private String googleSubjectSecret;

    /**
     * 设备标识 HMAC secret。
     */
    @NotBlank
    private String deviceSecret;

    /**
     * refresh token HMAC secret。
     */
    @NotBlank
    private String refreshTokenSecret;

}
