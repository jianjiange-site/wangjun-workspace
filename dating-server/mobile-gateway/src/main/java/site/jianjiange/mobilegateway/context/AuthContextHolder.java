package site.jianjiange.mobilegateway.context;

/**
 * 基于 ThreadLocal 的认证上下文持有器。
 */
public final class AuthContextHolder {

    private static final ThreadLocal<AuthContext> HOLDER = new ThreadLocal<>();

    /**
     * 工具类不允许实例化。
     */
    private AuthContextHolder() {
    }

    /**
     * 设置当前请求认证上下文。
     *
     * @param authContext 认证上下文
     */
    public static void set(AuthContext authContext) {
        HOLDER.set(authContext);
    }

    /**
     * 获取当前请求认证上下文。
     *
     * @return 当前认证上下文，未认证时返回 null
     */
    public static AuthContext get() {
        return HOLDER.get();
    }

    /**
     * 清理当前请求认证上下文。
     */
    public static void clear() {
        HOLDER.remove();
    }
}
