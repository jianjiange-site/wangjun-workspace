package site.jianjiange.mobilegateway.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * 手机验证码配置。
 */
@Configuration
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "gateway.sms")
public class SmsConfig {

    /**
     * 验证码有效期秒数。
     */
    @Positive
    private long codeTtlSeconds = 300;

    /**
     * 发送冷却秒数。
     */
    @Positive
    private long cooldownSeconds = 60;

    /**
     * 登录失败计数有效期秒数。
     */
    @Positive
    private long failTtlSeconds = 900;

    /**
     * 单个手机号在失败窗口内允许的最大验证码错误次数。
     */
    @Min(1)
    @Max(20)
    private int maxVerifyFailures = 5;

}
