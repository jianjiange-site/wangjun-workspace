package site.jianjiange.mobilegateway.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import site.jianjiange.mobilegateway.grpc.user.UserQueryServiceGrpc;
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
     * user-service 在 Nacos 中的服务名。
     */
    private String serviceId = "user-service";

    /**
     * user-service gRPC 端口。
     */
    @Positive
    private int port = 9090;

    /**
     * 是否优先通过 Nacos / Spring Discovery 解析 user-service 地址。
     */
    private boolean discoveryEnabled = true;

    /**
     * 调用 user-service 的 deadline 毫秒数。
     */
    @Positive
    private long deadlineMs = 1000;

    /**
     * 创建 user-service gRPC 连接通道。
     *
     * @param discoveryClientProvider Spring Discovery 客户端提供器
     * @return user-service 托管通道
     */
    @Bean(destroyMethod = "shutdown")
    @Qualifier("userServiceChannel")
    public ManagedChannel userServiceChannel(ObjectProvider<DiscoveryClient> discoveryClientProvider) {
        HostPort target = resolveTarget(discoveryClientProvider.getIfAvailable());
        return ManagedChannelBuilder.forAddress(target.host(), target.port())
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

    /**
     * 创建 user-service 用户查询阻塞调用桩。
     *
     * @param channel user-service gRPC 通道
     * @return user-service 查询阻塞调用桩
     */
    @Bean
    public UserQueryServiceGrpc.UserQueryServiceBlockingStub userQueryServiceBlockingStub(
            @Qualifier("userServiceChannel") ManagedChannel channel) {
        return UserQueryServiceGrpc.newBlockingStub(channel);
    }

    /**
     * 优先通过服务发现解析 user-service gRPC 地址，未发现时回退本地配置。
     *
     * @param discoveryClient Spring Discovery 客户端
     * @return gRPC host / port
     */
    private HostPort resolveTarget(DiscoveryClient discoveryClient) {
        if (!discoveryEnabled || discoveryClient == null || !StringUtils.hasText(serviceId)) {
            return new HostPort(host, port);
        }
        try {
            List<ServiceInstance> instances = discoveryClient.getInstances(serviceId);
            if (instances == null || instances.isEmpty()) {
                return new HostPort(host, port);
            }
            ServiceInstance instance = instances.getFirst();
            return new HostPort(instance.getHost(), grpcPort(instance.getMetadata()));
        } catch (RuntimeException exception) {
            return new HostPort(host, port);
        }
    }

    /**
     * 从 Nacos metadata 解析 gRPC 端口，未配置时回退本地端口。
     *
     * @param metadata 服务实例 metadata
     * @return gRPC 端口
     */
    private int grpcPort(Map<String, String> metadata) {
        String configuredPort = metadata == null ? null : metadata.get("grpc.port");
        if (!StringUtils.hasText(configuredPort)) {
            configuredPort = metadata == null ? null : metadata.get("grpc_port");
        }
        if (!StringUtils.hasText(configuredPort)) {
            return port;
        }
        try {
            int value = Integer.parseInt(configuredPort);
            return value > 0 ? value : port;
        } catch (NumberFormatException exception) {
            return port;
        }
    }

    /**
     * gRPC 目标地址。
     *
     * @param host 主机名或 IP
     * @param port 端口
     */
    private record HostPort(String host, int port) {
    }

}
