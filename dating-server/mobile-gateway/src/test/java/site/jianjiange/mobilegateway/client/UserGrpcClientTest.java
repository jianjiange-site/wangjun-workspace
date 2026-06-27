package site.jianjiange.mobilegateway.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.jianjiange.mobilegateway.config.GrpcClientConfig;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.exception.BusinessException;
import site.jianjiange.mobilegateway.grpc.user.RegisterOrInitializeRequest;
import site.jianjiange.mobilegateway.grpc.user.RegisterOrInitializeResponse;
import site.jianjiange.mobilegateway.grpc.user.UserRegisterServiceGrpc;
import site.jianjiange.mobilegateway.support.TraceIdContext;

/**
 * user-service gRPC 客户端异常映射和成功响应解析测试。
 */
class UserGrpcClientTest {

    private Server server;

    /**
     * 设置测试线程的 traceId，模拟入口过滤器已完成上下文初始化。
     */
    @BeforeEach
    void setUp() {
        TraceIdContext.setTraceId("trace-1");
    }

    /**
     * 每个测试结束后关闭内存 gRPC 服务。
     */
    @AfterEach
    void tearDown() {
        if (server != null) {
            server.shutdownNow();
        }
        TraceIdContext.clear();
    }

    /**
     * 验证 user-service 返回成功时客户端能解析结果。
     */
    @Test
    void registerOrInitializeParsesSuccessfulResponse() throws Exception {
        UserGrpcClient client = clientWithService(new UserRegisterServiceGrpc.UserRegisterServiceImplBase() {
            /**
             * 返回成功响应的 mock RegisterOrInitialize 实现。
             *
             * @param request 注册初始化请求
             * @param responseObserver gRPC 响应观察器
             */
            @Override
            public void registerOrInitialize(RegisterOrInitializeRequest request,
                                             StreamObserver<RegisterOrInitializeResponse> responseObserver) {
                assertThat(request.getTraceId()).isEqualTo("trace-1");
                responseObserver.onNext(RegisterOrInitializeResponse.newBuilder()
                        .setSuccess(request.getUserId() == 1001L)
                        .setCode(0)
                        .setMessage("ok")
                        .build());
                responseObserver.onCompleted();
            }
        }, 1000);

        UserGrpcClient.RegisterOrInitializeResult result =
                client.registerOrInitialize(1001L, "DEVICE", "device_login");

        assertThat(result.code()).isZero();
        assertThat(result.message()).isEqualTo("ok");
    }

    /**
     * 验证 gRPC deadline exceeded 会映射为 10701。
     */
    @Test
    void deadlineExceededMapsToGrpcTimeoutCode() throws Exception {
        UserGrpcClient client = clientWithService(new UserRegisterServiceGrpc.UserRegisterServiceImplBase() {
            /**
             * 返回 deadline exceeded 的 mock RegisterOrInitialize 实现。
             *
             * @param request 注册初始化请求
             * @param responseObserver gRPC 响应观察器
             */
            @Override
            public void registerOrInitialize(RegisterOrInitializeRequest request,
                                             StreamObserver<RegisterOrInitializeResponse> responseObserver) {
                responseObserver.onError(Status.DEADLINE_EXCEEDED.asRuntimeException());
            }
        }, 1000);

        assertThatThrownBy(() -> client.registerOrInitialize(1001L, "DEVICE", "device_login"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(ResultCode.DOWNSTREAM_GRPC_TIMEOUT));
    }

    /**
     * 验证普通 gRPC 异常会映射为 10702。
     */
    @Test
    void grpcExceptionMapsToGrpcErrorCode() throws Exception {
        UserGrpcClient client = clientWithService(new UserRegisterServiceGrpc.UserRegisterServiceImplBase() {
            /**
             * 返回 unavailable 异常的 mock RegisterOrInitialize 实现。
             *
             * @param request 注册初始化请求
             * @param responseObserver gRPC 响应观察器
             */
            @Override
            public void registerOrInitialize(RegisterOrInitializeRequest request,
                                             StreamObserver<RegisterOrInitializeResponse> responseObserver) {
                responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
            }
        }, 1000);

        assertThatThrownBy(() -> client.registerOrInitialize(1001L, "DEVICE", "device_login"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(ResultCode.DOWNSTREAM_GRPC_ERROR));
    }

    /**
     * 验证 user-service 返回失败状态会映射为 10703。
     */
    @Test
    void unsuccessfulResponseMapsToUserRegisterFailure() throws Exception {
        UserGrpcClient client = clientWithService(new UserRegisterServiceGrpc.UserRegisterServiceImplBase() {
            /**
             * 返回业务失败响应的 mock RegisterOrInitialize 实现。
             *
             * @param request 注册初始化请求
             * @param responseObserver gRPC 响应观察器
             */
            @Override
            public void registerOrInitialize(RegisterOrInitializeRequest request,
                                             StreamObserver<RegisterOrInitializeResponse> responseObserver) {
                responseObserver.onNext(RegisterOrInitializeResponse.newBuilder()
                        .setSuccess(false)
                        .setCode(10703)
                        .setMessage("init failed")
                        .build());
                responseObserver.onCompleted();
            }
        }, 1000);

        assertThatThrownBy(() -> client.registerOrInitialize(1001L, "DEVICE", "device_login"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(ResultCode.USER_REGISTER_INITIALIZE_FAILED));
    }

    /**
     * 使用指定 mock service 创建 user-service gRPC 客户端。
     *
     * @param service mock gRPC 服务实现
     * @param deadlineMs 客户端 deadline 毫秒数
     * @return 指向内存 gRPC 服务的客户端
     * @throws IOException 内存 gRPC 服务启动失败时抛出
     */
    private UserGrpcClient clientWithService(UserRegisterServiceGrpc.UserRegisterServiceImplBase service, long deadlineMs)
            throws IOException {
        String serverName = UUID.randomUUID().toString();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(service)
                .build()
                .start();
        GrpcClientConfig config = new GrpcClientConfig();
        config.setDeadlineMs(deadlineMs);
        return new UserGrpcClient(
                UserRegisterServiceGrpc.newBlockingStub(InProcessChannelBuilder.forName(serverName).directExecutor().build()),
                config);
    }
}
