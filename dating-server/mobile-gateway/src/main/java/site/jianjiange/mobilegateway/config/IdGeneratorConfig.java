package site.jianjiange.mobilegateway.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * 雪花 ID 生成器配置。
 */
@Configuration
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "gateway.id-generator")
public class IdGeneratorConfig {

    /**
     * 雪花 ID worker id，MVP 固定单实例时默认为 1。
     */
    @Min(0)
    @Max(31)
    private long workerId = 1;

    /**
     * 雪花 ID datacenter id，MVP 固定单实例时默认为 1。
     */
    @Min(0)
    @Max(31)
    private long datacenterId = 1;

}
