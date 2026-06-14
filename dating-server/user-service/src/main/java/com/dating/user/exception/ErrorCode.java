package com.dating.user.exception;

public enum ErrorCode {

    SMS_CODE_INVALID(10001, "短信验证码错误"),
    SMS_CODE_EXPIRED(10002, "短信验证码过期"),
    LOGIN_SESSION_EXPIRED(10003, "登录会话过期"),
    LIVENESS_FAILED(10004, "活体检测失败"),
    REFRESH_TOKEN_INVALID(10005, "refresh_token 无效"),
    USER_FROZEN(10006, "用户已被冻结"),
    USER_NOT_FOUND(10007, "用户不存在"),
    USER_STATUS_ABNORMAL(10008, "用户状态异常"),
    TAG_NOT_FOUND_OR_DISABLED(10009, "标签不存在或已禁用"),
    AVATAR_UPLOAD_FAILED(10010, "头像上传失败"),
    MINIO_ERROR(10011, "MinIO 服务异常"),
    ROCKETMQ_ERROR(10012, "RocketMQ 消息发送失败"),
    PHONE_ALREADY_BOUND(10013, "手机号已被其他用户绑定"),
    SMS_SEND_TOO_FREQUENT(10014, "发送验证码过于频繁"),
    AVATAR_AUDIT_REJECTED(10015, "头像审核未通过"),

    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未认证"),
    INTERNAL_ERROR(500, "系统异常");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
}
