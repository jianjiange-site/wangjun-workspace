package site.jianjiange.postservice.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 对象存储配置属性，用于构造 MinIO/S3 客户端和控制上传相关超时。
 *
 * @param provider 存储提供方标识
 * @param endpoint 对象存储访问地址
 * @param region 对象存储区域
 * @param pathStyleAccess 是否使用 path-style 访问方式
 * @param bucket 业务使用的存储桶
 * @param accessKey 访问密钥 ID
 * @param secretKey 访问密钥 Secret
 * @param uploadUrlTtl 预签名上传 URL 有效期
 * @param tempRetention 临时图片保留时长
 */
@Validated
@ConfigurationProperties(prefix = "dating.object-storage")
public record ObjectStorageProperties(
        @NotBlank String provider,
        @NotBlank String endpoint,
        @NotBlank String region,
        boolean pathStyleAccess,
        @NotBlank String bucket,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        @NotNull Duration uploadUrlTtl,
        @NotNull Duration tempRetention
) {
}
