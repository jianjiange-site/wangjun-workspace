package site.jianjiange.postservice.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * gRPC 基础配置属性，描述服务端端口、客户端超时和 Profile 服务名。
 *
 * @param serverPort gRPC 服务端口
 * @param clientDeadline gRPC 客户端默认超时时间
 * @param profileServiceName User/Profile 服务发现名称
 */
@Validated
@ConfigurationProperties(prefix = "dating.grpc")
public record GrpcConfig(
        int serverPort,
        @NotNull Duration clientDeadline,
        @NotBlank String profileServiceName
) {
}
