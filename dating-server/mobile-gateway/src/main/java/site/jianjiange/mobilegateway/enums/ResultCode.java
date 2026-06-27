package site.jianjiange.mobilegateway.enums;

import lombok.Getter;

/**
 * 网关统一业务码枚举。
 */
@Getter
public enum ResultCode {

    SUCCESS(0, "success"),
    SYSTEM_ERROR(10000, "系统异常"),
    PARAM_ERROR(10001, "参数错误"),
    REQUEST_BODY_ERROR(10002, "请求体格式错误"),

    UNAUTHORIZED(10100, "未登录"),
    FORBIDDEN(10101, "权限不足"),
    ACCOUNT_NOT_FOUND(10102, "账号不存在"),
    ACCOUNT_DISABLED(10103, "账号被禁用"),
    ACCOUNT_REGISTER_FAILED(10104, "账号注册失败"),

    SMS_CODE_INVALID(10200, "手机验证码无效或过期"),
    SMS_CODE_SEND_TOO_FREQUENT(10201, "手机验证码发送过于频繁"),
    SMS_CODE_VERIFY_TOO_FREQUENT(10202, "手机验证码校验过于频繁"),

    GOOGLE_TOKEN_INVALID(10300, "Google token 无效"),
    GOOGLE_TOKEN_EXPIRED(10301, "Google token 已过期"),
    GOOGLE_AUDIENCE_MISMATCH(10302, "Google audience 不匹配"),

    DEVICE_INVALID(10400, "设备号无效"),
    DEVICE_DISABLED(10401, "设备被禁用"),
    DEVICE_LOGIN_REJECTED(10402, "设备号快速登录被拒绝"),

    ACCESS_TOKEN_INVALID(10500, "access token 无效"),
    ACCESS_TOKEN_EXPIRED(10501, "access token 已过期"),
    ACCESS_TOKEN_BLACKLISTED(10502, "access token 已进入黑名单"),
    REFRESH_TOKEN_INVALID(10503, "refresh token 无效"),
    REFRESH_TOKEN_EXPIRED(10504, "refresh token 已过期"),
    REFRESH_TOKEN_REVOKED(10505, "refresh token 已撤销"),
    REFRESH_TOKEN_REUSED(10506, "refresh token 重复使用"),
    TOKEN_KEY_UNAVAILABLE(10507, "token 签名密钥不可用"),
    TOKEN_BLACKLIST_UNAVAILABLE(10508, "token 黑名单校验不可用"),

    RATE_LIMITED(10600, "触发限流"),

    REST_GRPC_CONVERT_FAILED(10700, "REST/gRPC 协议转换失败"),
    DOWNSTREAM_GRPC_TIMEOUT(10701, "下游 gRPC 超时"),
    DOWNSTREAM_GRPC_ERROR(10702, "下游 gRPC 异常"),
    USER_REGISTER_INITIALIZE_FAILED(10703, "user-service 用户注册 / 初始化失败"),

    BFF_PARTIAL_FAILED(10800, "BFF 部分数据失败"),
    BFF_FIELD_NOT_ALLOWED(10801, "请求了不允许裁剪的字段");

    /**
     * 数值型业务码。
     */
    private final int code;

    /**
     * 默认展示消息。
     */
    private final String message;

    /**
     * 创建业务码枚举项。
     *
     * @param code 数值型业务码
     * @param message 默认展示消息
     */
    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

}
