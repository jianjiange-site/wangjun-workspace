package site.jianjiange.mobilegateway.common;

import lombok.Getter;
import lombok.Setter;
import site.jianjiange.mobilegateway.enums.ResultCode;

/**
 * REST 接口统一响应包装对象。
 *
 * @param <T> 响应数据类型
 */
@Getter
@Setter
public class Result<T> {

    /**
     * 业务码。
     */
    private int code;

    /**
     * 展示消息。
     */
    private String message;

    /**
     * 响应数据。
     */
    private T data;

    /**
     * 请求链路 ID。
     */
    private String traceId;

    /**
     * Jackson 反序列化和框架代理使用的无参构造器。
     */
    public Result() {
    }

    /**
     * 创建固定结构的接口返回对象。
     *
     * @param code 业务码
     * @param message 展示消息
     * @param data 返回数据
     * @param traceId 请求链路 ID
     */
    private Result(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
    }

    /**
     * 创建成功响应。
     *
     * @param data 返回数据
     * @param traceId 请求链路 ID
     * @param <T> 返回数据类型
     * @return 成功结果
     */
    public static <T> Result<T> success(T data, String traceId) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data, traceId);
    }

    /**
     * 使用预登记业务码创建失败响应。
     *
     * @param resultCode 业务码枚举
     * @param traceId 请求链路 ID
     * @param <T> 返回数据类型
     * @return 失败结果
     */
    public static <T> Result<T> failure(ResultCode resultCode, String traceId) {
        return failure(resultCode.getCode(), resultCode.getMessage(), traceId);
    }

    /**
     * 使用指定业务码和消息创建失败响应。
     *
     * @param code 业务码
     * @param message 展示消息
     * @param traceId 请求链路 ID
     * @param <T> 返回数据类型
     * @return 失败结果
     */
    public static <T> Result<T> failure(int code, String message, String traceId) {
        return new Result<>(code, message, null, traceId);
    }

}
