package site.jianjiange.mobilegateway.client;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import site.jianjiange.mobilegateway.config.GrpcClientConfig;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.exception.BusinessException;
import site.jianjiange.mobilegateway.grpc.user.GetCurrentUserRequest;
import site.jianjiange.mobilegateway.grpc.user.GetCurrentUserResponse;
import site.jianjiange.mobilegateway.grpc.user.UserQueryServiceGrpc;
import site.jianjiange.mobilegateway.grpc.user.RegisterOrInitializeRequest;
import site.jianjiange.mobilegateway.grpc.user.RegisterOrInitializeResponse;
import site.jianjiange.mobilegateway.grpc.user.UserRegisterServiceGrpc;
import site.jianjiange.mobilegateway.support.TraceIdContext;

/**
 * user-service gRPC 客户端。
 */
@Component
public class UserGrpcClient {

    private static final Metadata.Key<String> TRACE_ID_METADATA_KEY =
            Metadata.Key.of("trace_id", Metadata.ASCII_STRING_MARSHALLER);

    private final UserRegisterServiceGrpc.UserRegisterServiceBlockingStub stub;
    private final UserQueryServiceGrpc.UserQueryServiceBlockingStub queryStub;
    private final GrpcClientConfig config;

    /**
     * 创建 user-service gRPC 客户端。
     *
     * @param stub user-service 注册初始化阻塞调用桩
     * @param queryStub user-service 查询阻塞调用桩
     * @param config user-service gRPC 配置
     */
    @Autowired
    public UserGrpcClient(UserRegisterServiceGrpc.UserRegisterServiceBlockingStub stub,
                          UserQueryServiceGrpc.UserQueryServiceBlockingStub queryStub,
                          GrpcClientConfig config) {
        this.stub = stub;
        this.queryStub = queryStub;
        this.config = config;
    }

    /**
     * 创建只包含注册初始化调用桩的测试客户端。
     *
     * @param stub user-service 注册初始化阻塞调用桩
     * @param config user-service gRPC 配置
     */
    UserGrpcClient(UserRegisterServiceGrpc.UserRegisterServiceBlockingStub stub, GrpcClientConfig config) {
        this(stub, null, config);
    }

    /**
     * 创建只包含用户查询调用桩的测试客户端。
     *
     * @param queryStub user-service 查询阻塞调用桩
     * @param config user-service gRPC 配置
     */
    UserGrpcClient(UserQueryServiceGrpc.UserQueryServiceBlockingStub queryStub, GrpcClientConfig config) {
        this(null, queryStub, config);
    }

    /**
     * 调用 user-service 按 userId 幂等注册或初始化用户资料。
     *
     * @param userId 网关生成的全局用户 ID
     * @param accountType 账号类型
     * @param registerSource 注册来源
     * @return 注册初始化结果
     */
    public RegisterOrInitializeResult registerOrInitialize(long userId, String accountType,
                                                           String registerSource) {
        String traceId = TraceIdContext.currentOrCreate(TraceIdContext.getTraceId());
        RegisterOrInitializeRequest request = RegisterOrInitializeRequest.newBuilder()
                .setUserId(userId)
                .setAccountType(accountType)
                .setRegisterSource(registerSource)
                .setTraceId(traceId)
                .build();
        Metadata metadata = new Metadata();
        metadata.put(TRACE_ID_METADATA_KEY, traceId);
        try {
            RegisterOrInitializeResponse response = stub
                    .withDeadlineAfter(config.getDeadlineMs(), TimeUnit.MILLISECONDS)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                    .registerOrInitialize(request);
            if (!response.getSuccess()) {
                throw new BusinessException(ResultCode.USER_REGISTER_INITIALIZE_FAILED, response.getMessage());
            }
            return new RegisterOrInitializeResult(response.getCode(), response.getMessage());
        } catch (StatusRuntimeException exception) {
            if (exception.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
                throw new BusinessException(ResultCode.DOWNSTREAM_GRPC_TIMEOUT);
            }
            throw new BusinessException(ResultCode.DOWNSTREAM_GRPC_ERROR);
        }
    }

    /**
     * 调用 user-service 查询当前用户资料。
     *
     * @param request 当前用户查询请求
     * @param metadata 下游调用 metadata
     * @return user-service 当前用户资料响应
     */
    public GetCurrentUserResponse getCurrentUser(GetCurrentUserRequest request, Metadata metadata) {
        try {
            return queryStub
                    .withDeadlineAfter(config.getDeadlineMs(), TimeUnit.MILLISECONDS)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                    .getCurrentUser(request);
        } catch (StatusRuntimeException exception) {
            if (exception.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
                throw new BusinessException(ResultCode.DOWNSTREAM_GRPC_TIMEOUT);
            }
            throw new BusinessException(ResultCode.DOWNSTREAM_GRPC_ERROR);
        } catch (RuntimeException exception) {
            throw new BusinessException(ResultCode.DOWNSTREAM_GRPC_ERROR);
        }
    }

    /**
     * user-service 注册初始化调用结果。
     *
     * @param code 下游返回码
     * @param message 下游返回消息
     */
    public record RegisterOrInitializeResult(int code, String message) {
    }
}
