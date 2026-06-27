package site.jianjiange.mobilegateway.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import site.jianjiange.mobilegateway.grpc.user.UserRegisterServiceGrpc;

/**
 * user-service gRPC 客户端配置和调用桩 Bean 定义。
 */
@Configuration
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "gateway.grpc.user-service")
public class GrpcClientConfig {

    /**
     * user-service gRPC 主机名或 IP。
     */
    private String host = "localhost";

    /**
     * user-service gRPC 端口。
     */
    @Positive
    private int port = 9090;

    /**
     * 调用 user-service 的 deadline 毫秒数。
     */
    @Positive
    private long deadlineMs = 1000;

    /**
     * 创建 user-service gRPC 连接通道。
     *
     * @return user-service 托管通道
     */
    @Bean(destroyMethod = "shutdown")
    @Qualifier("userServiceChannel")
    public ManagedChannel userServiceChannel() {
        return ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
    }

    /**
     * 创建 user-service 注册初始化阻塞调用桩。
     *
     * @param channel user-service gRPC 通道
     * @return user-service 阻塞调用桩
     */
    @Bean
    public UserRegisterServiceGrpc.UserRegisterServiceBlockingStub userRegisterServiceBlockingStub(
            @Qualifier("userServiceChannel") ManagedChannel channel) {
        return UserRegisterServiceGrpc.newBlockingStub(channel);
    }

}
