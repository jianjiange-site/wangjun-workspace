package site.jianjiange.postservice.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据库数据源配置，显式创建 PostgreSQL/H2 使用的 Hikari 连接池。
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    /**
     * 绑定 Spring 标准数据源配置，例如 JDBC URL、用户名、密码和驱动类。
     *
     * @return 数据源基础配置属性
     */
    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * 创建 Hikari 数据源，并绑定 spring.datasource.hikari 下的连接池参数。
     *
     * @param dataSourceProperties 数据源基础配置属性
     * @return Hikari 数据源
     */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}
