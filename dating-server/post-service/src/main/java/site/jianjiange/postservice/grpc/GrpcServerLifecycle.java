package site.jianjiange.postservice.grpc;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.config.GrpcConfig;

/**
 * 内置 gRPC 服务端生命周期管理。
 */
@Component
@ConditionalOnProperty(prefix = "dating.grpc", name = "server-enabled", havingValue = "true", matchIfMissing = true)
public class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private final GrpcConfig grpcConfig;
    private final List<BindableService> services;
    private final GrpcMetadataInterceptor metadataInterceptor;
    private Server server;
    private boolean running;

    /**
     * 创建 gRPC 服务端生命周期。
     */
    public GrpcServerLifecycle(
            GrpcConfig grpcConfig,
            List<BindableService> services,
            GrpcMetadataInterceptor metadataInterceptor) {
        this.grpcConfig = grpcConfig;
        this.services = services;
        this.metadataInterceptor = metadataInterceptor;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        try {
            ServerBuilder<?> builder = ServerBuilder.forPort(grpcConfig.serverPort());
            services.forEach(service -> builder.addService(ServerInterceptors.intercept(
                    service,
                    metadataInterceptor)));
            server = builder.build().start();
            running = true;
            log.info("gRPC 服务端已启动，port={}, serviceCount={}", grpcConfig.serverPort(), services.size());
        } catch (IOException ex) {
            throw new IllegalStateException("gRPC 服务端启动失败", ex);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.shutdown();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
