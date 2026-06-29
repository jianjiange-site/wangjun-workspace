package site.jianjiange.mobilegateway.converter;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import site.jianjiange.mobilegateway.context.AuthContext;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.exception.BusinessException;
import site.jianjiange.mobilegateway.grpc.user.GetCurrentUserRequest;
import site.jianjiange.mobilegateway.grpc.user.GetCurrentUserResponse;
import site.jianjiange.mobilegateway.vo.CurrentUserVO;

/**
 * REST 和 user-service gRPC 协议对象转换器。
 */
@Component
public class RestGrpcConverter {

    /**
     * 将当前认证上下文转换为 GetCurrentUser proto request。
     *
     * @param context 当前认证上下文
     * @return 当前用户查询 gRPC 请求
     */
    public GetCurrentUserRequest toGetCurrentUserRequest(AuthContext context) {
        if (context == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (context.userId() <= 0 || !StringUtils.hasText(context.traceId())) {
            throw new BusinessException(ResultCode.REST_GRPC_CONVERT_FAILED);
        }
        return GetCurrentUserRequest.newBuilder()
                .setUserId(context.userId())
                .setTraceId(context.traceId())
                .build();
    }

    /**
     * 将 GetCurrentUser proto response 转换为对外 REST VO。
     *
     * @param response user-service 当前用户资料响应
     * @return 当前用户 REST VO
     */
    public CurrentUserVO toCurrentUserVO(GetCurrentUserResponse response) {
        if (response == null || response.getUserId() <= 0) {
            throw new BusinessException(ResultCode.REST_GRPC_CONVERT_FAILED);
        }
        return new CurrentUserVO(response.getUserId(), response.getNickname(), response.getAvatarUrl(),
                response.getBio());
    }
}
