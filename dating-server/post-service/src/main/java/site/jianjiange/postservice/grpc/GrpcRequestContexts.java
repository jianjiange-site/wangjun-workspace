package site.jianjiange.postservice.grpc;

import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Status;

/**
 * gRPC metadata 上下文工具。
 */
public final class GrpcRequestContexts {

    public static final Context.Key<GrpcRequestContext> REQUEST_CONTEXT_KEY =
            Context.key("post-service-request-context");
    public static final Metadata.Key<String> USER_ID_METADATA_KEY =
            Metadata.Key.of("user_id", Metadata.ASCII_STRING_MARSHALLER);
    public static final Metadata.Key<String> ROLE_METADATA_KEY =
            Metadata.Key.of("role", Metadata.ASCII_STRING_MARSHALLER);
    public static final Metadata.Key<String> REQUEST_ID_METADATA_KEY =
            Metadata.Key.of("request_id", Metadata.ASCII_STRING_MARSHALLER);

    private GrpcRequestContexts() {
    }

    /**
     * 从当前 gRPC Context 读取已认证用户 ID。
     *
     * @return 当前用户 ID
     */
    public static Long currentUserId() {
        GrpcRequestContext context = REQUEST_CONTEXT_KEY.get();
        if (context == null || context.userId() == null || context.userId() <= 0) {
            throw Status.UNAUTHENTICATED
                    .withDescription("metadata user_id 必须为正整数")
                    .asRuntimeException();
        }
        return context.userId();
    }

    /**
     * 从当前 gRPC Context 读取请求 ID。
     *
     * @return 请求 ID；未传时返回空字符串
     */
    public static String currentRequestId() {
        GrpcRequestContext context = REQUEST_CONTEXT_KEY.get();
        return context == null ? "" : context.requestId();
    }

    /**
     * 将 metadata 转换为请求上下文。
     *
     * @param metadata gRPC metadata
     * @return 请求上下文
     */
    public static GrpcRequestContext fromMetadata(Metadata metadata) {
        return new GrpcRequestContext(
                parseUserId(metadata.get(USER_ID_METADATA_KEY)),
                blankToEmpty(metadata.get(ROLE_METADATA_KEY)),
                blankToEmpty(metadata.get(REQUEST_ID_METADATA_KEY)));
    }

    private static Long parseUserId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
