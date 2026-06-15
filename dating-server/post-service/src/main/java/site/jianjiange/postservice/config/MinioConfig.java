package site.jianjiange.postservice.config;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端配置，基于对象存储属性创建 S3 兼容客户端。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ObjectStorageProperties.class)
public class MinioConfig {

    /**
     * 创建 MinIO 客户端。
     *
     * @param properties 对象存储配置属性
     * @return MinIO 客户端实例
     */
    @Bean
    public MinioClient minioClient(ObjectStorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .region(properties.region())
                .build();
    }
}
