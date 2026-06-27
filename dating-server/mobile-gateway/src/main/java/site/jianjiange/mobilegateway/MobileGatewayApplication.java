package site.jianjiange.mobilegateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;

/**
 * Mobile Gateway 启动入口。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("site.jianjiange.mobilegateway.mapper")
public class MobileGatewayApplication {

    /**
     * 启动 mobile-gateway Spring Boot 应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(MobileGatewayApplication.class, args);
    }

    /**
     * 明确启用驼峰字段到下划线列名映射。
     *
     * @return MyBatis 配置定制器
     */
    @Bean
    public ConfigurationCustomizer mybatisConfigurationCustomizer() {
        return configuration -> configuration.setMapUnderscoreToCamelCase(true);
    }
}
