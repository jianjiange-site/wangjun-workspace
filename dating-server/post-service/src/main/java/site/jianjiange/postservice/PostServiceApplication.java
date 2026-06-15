package site.jianjiange.postservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;

/**
 * Post 服务启动入口，负责加载 Spring Boot 应用上下文并扫描 MyBatis-Plus Mapper。
 */
@SpringBootApplication
@MapperScan("site.jianjiange.postservice.mapper")
public class PostServiceApplication {

    /**
     * 启动 Post 服务应用。
     *
     * @param args 命令行启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(PostServiceApplication.class, args);
    }
}
