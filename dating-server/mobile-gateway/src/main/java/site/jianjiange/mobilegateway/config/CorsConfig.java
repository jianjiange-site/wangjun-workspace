package site.jianjiange.mobilegateway.config;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 跨域配置绑定和 Spring MVC 跨域规则注册器。
 */
@Configuration
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "gateway.cors")
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 允许跨域访问的来源列表。
     */
    private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:3000"));

    /**
     * 允许跨域调用的 HTTP 方法列表。
     */
    private List<String> allowedMethods = new ArrayList<>(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));

    /**
     * 允许客户端携带的请求头列表。
     */
    private List<String> allowedHeaders = new ArrayList<>(List.of("*"));

    /**
     * 允许浏览器读取的响应头列表。
     */
    private List<String> exposedHeaders = new ArrayList<>(List.of("X-Trace-Id"));

    /**
     * 是否允许跨域请求携带凭证。
     */
    private boolean allowCredentials = true;

    /**
     * 预检请求缓存秒数。
     */
    private long maxAgeSeconds = 3600;

    /**
     * 按 gateway.cors 配置注册全局跨域规则。
     *
     * @param registry Spring MVC CORS 注册器
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods(allowedMethods.toArray(String[]::new))
                .allowedHeaders(allowedHeaders.toArray(String[]::new))
                .exposedHeaders(exposedHeaders.toArray(String[]::new))
                .allowCredentials(allowCredentials)
                .maxAge(maxAgeSeconds);
    }
}
