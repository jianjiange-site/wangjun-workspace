package site.jianjiange.mobilegateway.grpc.adapter;

import io.grpc.Metadata;
import org.springframework.stereotype.Component;
import site.jianjiange.mobilegateway.client.UserGrpcClient;
import site.jianjiange.mobilegateway.context.AuthContext;
import site.jianjiange.mobilegateway.context.AuthContextHolder;
import site.jianjiange.mobilegateway.converter.RestGrpcConverter;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.exception.BusinessException;
import site.jianjiange.mobilegateway.grpc.user.GetCurrentUserRequest;
import site.jianjiange.mobilegateway.grpc.user.GetCurrentUserResponse;
import site.jianjiange.mobilegateway.vo.CurrentUserVO;

/**
 * user-service gRPC adapter，负责认证上下文透传和 REST/gRPC 转换编排。
 */
@Component
public class UserGrpcAdapter {

    private static final Metadata.Key<String> TRACE_ID_METADATA_KEY =
            Metadata.Key.of("trace_id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> USER_ID_METADATA_KEY =
            Metadata.Key.of("user_id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> ACCOUNT_ID_METADATA_KEY =
            Metadata.Key.of("account_id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> DEVICE_ID_METADATA_KEY =
            Metadata.Key.of("device_id", Metadata.ASCII_STRING_MARSHALLER);

    private final UserGrpcClient userGrpcClient;
    private final RestGrpcConverter converter;

    /**
     * 创建 user-service gRPC adapter。
     *
     * @param userGrpcClient user-service gRPC 客户端
     * @param converter REST/gRPC 转换器
     */
    public UserGrpcAdapter(UserGrpcClient userGrpcClient, RestGrpcConverter converter) {
        this.userGrpcClient = userGrpcClient;
        this.converter = converter;
    }

    /**
     * 查询当前用户资料。
     *
     * @return 当前用户 REST VO
     */
    public CurrentUserVO getCurrentUser() {
        AuthContext context = AuthContextHolder.get();
        if (context == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        GetCurrentUserRequest request = converter.toGetCurrentUserRequest(context);
        GetCurrentUserResponse response = userGrpcClient.getCurrentUser(request, metadata(context));
        return converter.toCurrentUserVO(response);
    }

    /**
     * 从认证上下文构造下游 metadata。
     *
     * @param context 当前认证上下文
     * @return gRPC metadata
     */
    private Metadata metadata(AuthContext context) {
        Metadata metadata = new Metadata();
        metadata.put(TRACE_ID_METADATA_KEY, context.traceId());
        metadata.put(USER_ID_METADATA_KEY, String.valueOf(context.userId()));
        metadata.put(ACCOUNT_ID_METADATA_KEY, String.valueOf(context.accountId()));
        metadata.put(DEVICE_ID_METADATA_KEY, String.valueOf(context.deviceId()));
        return metadata;
    }
}
