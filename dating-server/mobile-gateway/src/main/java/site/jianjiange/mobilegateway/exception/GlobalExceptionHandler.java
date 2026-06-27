package site.jianjiange.mobilegateway.exception;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import site.jianjiange.mobilegateway.common.Result;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.support.TraceIdContext;

/**
 * 全局异常处理器，将业务异常和框架异常转换为统一响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 将业务异常转换为统一失败响应。
     *
     * @param exception 业务异常
     * @return 统一失败响应
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException exception) {
        ResultCode resultCode = exception.getResultCode();
        return Result.failure(resultCode.getCode(), exception.getMessage(), TraceIdContext.getTraceId());
    }

    /**
     * 将参数校验异常转换为参数错误业务码。
     *
     * @param exception 参数异常
     * @return 参数错误响应
     */
    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class,
            MissingServletRequestParameterException.class
    })
    public Result<Void> handleParamException(Exception exception) {
        log.debug("Request parameter validation failed, traceId={}", TraceIdContext.getTraceId(), exception);
        return Result.failure(ResultCode.PARAM_ERROR, TraceIdContext.getTraceId());
    }

    /**
     * 将请求体解析异常转换为请求体格式错误业务码。
     *
     * @param exception 请求体解析异常
     * @return 请求体格式错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleRequestBodyException(HttpMessageNotReadableException exception) {
        log.debug("Request body parse failed, traceId={}", TraceIdContext.getTraceId(), exception);
        return Result.failure(ResultCode.REQUEST_BODY_ERROR, TraceIdContext.getTraceId());
    }

    /**
     * 将未预期异常转换为系统异常业务码，避免向客户端泄漏堆栈。
     *
     * @param exception 未预期异常
     * @return 系统异常响应
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception exception) {
        log.error("Unhandled request exception, traceId={}", TraceIdContext.getTraceId(), exception);
        return Result.failure(ResultCode.SYSTEM_ERROR, TraceIdContext.getTraceId());
    }
}
