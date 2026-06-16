package site.jianjiange.postservice.exception;

/**
 * Post 服务业务异常，携带可被接口层映射的错误码。
 */
public class BusinessException extends RuntimeException {

    private final PostErrorCode errorCode;

    /**
     * 创建带错误码和错误信息的业务异常。
     *
     * @param errorCode 业务错误码
     * @param message 错误信息
     */
    public BusinessException(PostErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 返回业务错误码。
     *
     * @return 业务错误码
     */
    public PostErrorCode getErrorCode() {
        return errorCode;
    }
}
