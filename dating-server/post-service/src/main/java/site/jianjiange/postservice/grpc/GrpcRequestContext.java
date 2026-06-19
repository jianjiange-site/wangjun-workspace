package site.jianjiange.postservice.grpc;

/**
 * gRPC 请求上下文，从 metadata 提取后放入当前调用 Context。
 *
 * @param userId 当前用户 ID
 * @param role 当前用户角色
 * @param requestId 请求追踪 ID
 */
public record GrpcRequestContext(
        Long userId,
        String role,
        String requestId
) {
}
