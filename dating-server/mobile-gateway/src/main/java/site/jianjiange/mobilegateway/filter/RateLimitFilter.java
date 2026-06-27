package site.jianjiange.mobilegateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import site.jianjiange.mobilegateway.common.Result;
import site.jianjiange.mobilegateway.config.RateLimitConfig;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.service.RateLimitService;
import site.jianjiange.mobilegateway.support.TraceIdContext;

/**
 * 入口基础限流过滤器。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitConfig config;
    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    /**
     * 创建入口限流过滤器。
     *
     * @param config 限流配置
     * @param rateLimitService 限流服务
     * @param objectMapper JSON 序列化器
     */
    public RateLimitFilter(RateLimitConfig config, RateLimitService rateLimitService, ObjectMapper objectMapper) {
        this.config = config;
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
    }

    /**
     * 对非预检入口请求执行 IP + path 维度基础限流。
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
        if (!config.isEnabled() || isPreflight(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        String key = clientIp(request) + ":" + request.getMethod() + ":" + request.getRequestURI();
        if (rateLimitService.isAllowed("ip-path", key)) {
            filterChain.doFilter(request, response);
            return;
        }
        writeRateLimitResponse(response);
    }

    /**
     * 判断请求是否为 CORS 预检请求。
     *
     * @param request 当前 HTTP 请求
     * @return 是预检请求返回 true
     */
    private boolean isPreflight(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                && request.getHeader("Access-Control-Request-Method") != null;
    }

    /**
     * 提取客户端 IP，优先使用代理转发头。
     *
     * @param request 当前 HTTP 请求
     * @return 客户端 IP
     */
    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 写入触发限流时的统一业务响应。
     *
     * @param response 当前 HTTP 响应
     * @throws IOException 响应写入异常
     */
    private void writeRateLimitResponse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.failure(ResultCode.RATE_LIMITED, TraceIdContext.getTraceId())));
    }
}
