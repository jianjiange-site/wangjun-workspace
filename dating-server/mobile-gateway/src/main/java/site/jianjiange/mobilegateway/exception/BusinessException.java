package site.jianjiange.mobilegateway.exception;

import lombok.Getter;
import site.jianjiange.mobilegateway.enums.ResultCode;

/**
 * 携带业务码的网关业务异常。
 */
@Getter
public class BusinessException extends RuntimeException {

    /**
     * 异常对应的业务码。
     */
    private final ResultCode resultCode;

    /**
     * 使用预登记业务码创建业务异常。
     *
     * @param resultCode 业务码枚举
     */
    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }

    /**
     * 使用预登记业务码和自定义消息创建业务异常。
     *
     * @param resultCode 业务码枚举
     * @param message 自定义展示消息
     */
    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

}
