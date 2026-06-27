package site.jianjiange.mobilegateway.support;

import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

/**
 * 基于 ThreadLocal 的请求 traceId 上下文。
 */
public final class TraceIdContext {

    /**
     * traceId 请求和响应头名称。
     */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * 日志 MDC 中的 traceId key。
     */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    private static final ThreadLocal<String> TRACE_ID_HOLDER = new ThreadLocal<>();

    /**
     * 工具类不允许实例化。
     */
    private TraceIdContext() {
    }

    /**
     * 获取传入 traceId，若为空则生成新的 traceId 并写入上下文。
     *
     * @param traceId 请求头中的 traceId
     * @return 当前请求使用的 traceId
     */
    public static String currentOrCreate(String traceId) {
        String value = StringUtils.hasText(traceId) ? traceId.trim() : generate();
        setTraceId(value);
        return value;
    }

    /**
     * 获取当前线程保存的 traceId。
     *
     * @return 当前 traceId
     */
    public static String getTraceId() {
        return TRACE_ID_HOLDER.get();
    }

    /**
     * 设置当前线程和日志 MDC 中的 traceId。
     *
     * @param traceId 请求链路 ID
     */
    public static void setTraceId(String traceId) {
        TRACE_ID_HOLDER.set(traceId);
        MDC.put(TRACE_ID_MDC_KEY, traceId);
    }

    /**
     * 清理当前线程和日志 MDC 中的 traceId。
     */
    public static void clear() {
        TRACE_ID_HOLDER.remove();
        MDC.remove(TRACE_ID_MDC_KEY);
    }

    /**
     * 生成无连字符 UUID traceId。
     *
     * @return 新 traceId
     */
    private static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
