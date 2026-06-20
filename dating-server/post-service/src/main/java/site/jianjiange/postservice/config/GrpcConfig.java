package site.jianjiange.postservice.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * gRPC 客户端配置属性，描述客户端超时和 Profile 服务名。
 *
 * @param clientDeadline gRPC 客户端默认超时时间
 * @param profileServiceName User/Profile 服务发现名称
 */
@Validated
@ConfigurationProperties(prefix = "dating.grpc")
public record GrpcConfig(
        @NotNull Duration clientDeadline,
        @NotBlank String profileServiceName
) {
}
