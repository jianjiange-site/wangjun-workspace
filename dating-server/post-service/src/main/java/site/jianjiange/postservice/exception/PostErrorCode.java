package site.jianjiange.postservice.exception;

/**
 * Post 服务业务错误码，供 service 层和后续 gRPC 层统一识别失败原因。
 */
public enum PostErrorCode {

    INVALID_ARGUMENT,
    IDEMPOTENT_CONFLICT,
    POST_NOT_FOUND,
    POST_FORBIDDEN,
    IMAGE_LIMIT_EXCEEDED,
    IMAGE_NOT_FOUND,
    IMAGE_FORBIDDEN,
    IMAGE_STATUS_INVALID
}
