package site.jianjiange.mobilegateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import site.jianjiange.mobilegateway.support.TraceIdContext;

/**
 * traceId 过滤器，负责生成、透传并清理请求链路 ID。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /**
     * 为入口请求生成或透传 traceId，并在请求结束后清理线程上下文。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param filterChain Servlet 过滤器链
     * @throws ServletException 过滤器链处理异常
     * @throws IOException IO 处理异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = TraceIdContext.currentOrCreate(request.getHeader(TraceIdContext.TRACE_ID_HEADER));
        response.setHeader(TraceIdContext.TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceIdContext.clear();
        }
    }
}
