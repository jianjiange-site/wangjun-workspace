package site.jianjiange.mobilegateway.manager;

/**
 * 认证域常量定义。
 */
public final class AuthConstants {

    /**
     * 设备账号类型。
     */
    public static final String ACCOUNT_TYPE_DEVICE = "DEVICE";

    /**
     * 设备快速登录注册来源。
     */
    public static final String REGISTER_SOURCE_DEVICE_LOGIN = "device_login";

    /**
     * 正常状态。
     */
    public static final int STATUS_NORMAL = 1;

    /**
     * 禁用状态。
     */
    public static final int STATUS_DISABLED = 2;

    /**
     * user-service 初始化待处理状态。
     */
    public static final int REGISTER_STATUS_PENDING = 0;

    /**
     * user-service 初始化完成状态。
     */
    public static final int REGISTER_STATUS_DONE = 1;

    /**
     * 未删除标记。
     */
    public static final int DELETED_NO = 0;

    /**
     * refresh token ACTIVE 状态。
     */
    public static final int REFRESH_STATUS_ACTIVE = 1;

    /**
     * 禁止实例化常量类。
     */
    private AuthConstants() {
    }
}
