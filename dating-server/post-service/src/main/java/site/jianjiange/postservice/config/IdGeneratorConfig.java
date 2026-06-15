package site.jianjiange.postservice.config;

import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus ID 生成器配置，统一使用雪花算法生成 BIGINT 主键。
 */
@Configuration(proxyBeanMethods = false)
public class IdGeneratorConfig {

    /**
     * 创建 MyBatis-Plus 主键生成器。
     *
     * @return 全局主键生成器
     */
    @Bean
    public IdentifierGenerator identifierGenerator() {
        return DefaultIdentifierGenerator.getInstance();
    }
}
