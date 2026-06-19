package site.jianjiange.postservice.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.exception.BusinessException;
import site.jianjiange.postservice.exception.PostErrorCode;

/**
 * 将 service 层异常转换为 gRPC 状态异常。
 */
@Component
public class GrpcExceptionMapper {

    private static final Logger log = LoggerFactory.getLogger(GrpcExceptionMapper.class);

    /**
     * 转换异常。
     *
     * @param throwable 原始异常
     * @return gRPC 异常
     */
    public StatusRuntimeException toStatusException(Throwable throwable) {
        if (throwable instanceof StatusRuntimeException statusRuntimeException) {
            return statusRuntimeException;
        }
        if (throwable instanceof BusinessException businessException) {
            return mapBusinessException(businessException);
        }
        log.warn("gRPC 接口未预期异常，errorType={}, errorMessage={}",
                throwable.getClass().getSimpleName(), throwable.getMessage(), throwable);
        return Status.INTERNAL
                .withDescription("系统繁忙，请稍后重试")
                .withCause(throwable)
                .asRuntimeException();
    }

    private StatusRuntimeException mapBusinessException(BusinessException exception) {
        Status status = switch (exception.getErrorCode()) {
            case INVALID_ARGUMENT -> Status.INVALID_ARGUMENT;
            case IDEMPOTENT_CONFLICT, IMAGE_STATUS_INVALID -> Status.ALREADY_EXISTS;
            case POST_NOT_FOUND, IMAGE_NOT_FOUND, COMMENT_NOT_FOUND -> Status.NOT_FOUND;
            case POST_FORBIDDEN, IMAGE_FORBIDDEN, COMMENT_FORBIDDEN -> Status.PERMISSION_DENIED;
            case POST_NOT_PUBLISHED, IMAGE_LIMIT_EXCEEDED, IMAGE_NOT_UPLOADED, IMAGE_CONTENT_TYPE_INVALID ->
                    Status.FAILED_PRECONDITION;
            case LIKE_TEMPORARILY_UNAVAILABLE, MINIO_OPERATION_FAILED, COMMENT_TEMPORARILY_UNAVAILABLE ->
                    Status.UNAVAILABLE;
        };
        PostErrorCode errorCode = exception.getErrorCode();
        return status.withDescription(errorCode.name() + ": " + exception.getMessage())
                .withCause(exception)
                .asRuntimeException();
    }
}
