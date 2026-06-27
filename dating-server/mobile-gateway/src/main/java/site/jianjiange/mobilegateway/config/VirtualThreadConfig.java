package site.jianjiange.mobilegateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * 虚拟线程配置观测器，启动后记录当前开关状态。
 */
@Configuration
@Slf4j
public class VirtualThreadConfig {


    @Value("${spring.threads.virtual.enabled:false}")
    private boolean virtualThreadEnabled;

    /**
     * 启动后记录虚拟线程开关状态，便于本地验收。
     */
    @PostConstruct
    public void logVirtualThreadStatus() {
        log.info("Spring virtual thread enabled={}", virtualThreadEnabled);
    }
}
