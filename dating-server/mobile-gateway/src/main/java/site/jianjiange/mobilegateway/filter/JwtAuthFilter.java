package site.jianjiange.mobilegateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import site.jianjiange.mobilegateway.common.Result;
import site.jianjiange.mobilegateway.context.AuthContext;
import site.jianjiange.mobilegateway.context.AuthContextHolder;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.exception.BusinessException;
import site.jianjiange.mobilegateway.service.JwtService;
import site.jianjiange.mobilegateway.support.RedisKeyFactory;
import site.jianjiange.mobilegateway.support.TraceIdContext;

/**
 * JWT 鉴权过滤器，负责 access token 验签、blacklist 校验和认证上下文注入。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory redisKeyFactory;
    private final ObjectMapper objectMapper;

    /**
     * 创建 JWT 鉴权过滤器。
     *
     * @param jwtService JWT 服务
     * @param redisTemplate Redis 字符串客户端
     * @param redisKeyFactory Redis key 工厂
     * @param objectMapper JSON 序列化器
     */
    public JwtAuthFilter(JwtService jwtService, StringRedisTemplate redisTemplate,
                         RedisKeyFactory redisKeyFactory, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.redisTemplate = redisTemplate;
        this.redisKeyFactory = redisKeyFactory;
        this.objectMapper = objectMapper;
    }

    /**
     * 对业务 API 执行 JWT 鉴权。
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
        if (shouldSkip(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            String token = bearerToken(request);
            JwtService.VerifiedAccessToken verified = jwtService.verifyAccessToken(token);
            ensureNotBlacklisted(verified.jti());
            AuthContextHolder.set(new AuthContext(verified.userId(), verified.accountId(), verified.deviceId(),
                    TraceIdContext.getTraceId(), verified.jti(), verified.expiresAt()));
            filterChain.doFilter(request, response);
        } catch (BusinessException exception) {
            writeFailure(response, exception.getResultCode());
        } finally {
            AuthContextHolder.clear();
        }
    }

    /**
     * 判断当前请求是否不需要 JWT 鉴权。
     *
     * @param request 当前 HTTP 请求
     * @return 需要跳过鉴权时返回 true
     */
    private boolean shouldSkip(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/")) {
            return true;
        }
        return "/api/v1/auth/sms-code".equals(path)
                || "/api/v1/auth/login/phone".equals(path)
                || "/api/v1/auth/login/device".equals(path)
                || "/api/v1/auth/refresh".equals(path);
    }

    /**
     * 从 Authorization 头提取 Bearer token。
     *
     * @param request 当前 HTTP 请求
     * @return access token 明文
     */
    private String bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return token;
    }

    /**
     * 检查 access token jti 是否已进入 Redis blacklist。Redis 异常时 fail closed。
     *
     * @param jti access token 唯一标识
     */
    private void ensureNotBlacklisted(String jti) {
        String redisKey = redisKeyFactory.jwtBlacklist(jti);
        try {
            Boolean blacklisted = redisTemplate.hasKey(redisKey);
            if (Boolean.TRUE.equals(blacklisted)) {
                throw new BusinessException(ResultCode.ACCESS_TOKEN_BLACKLISTED);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("JWT blacklist Redis lookup failed, jti={}", jti, exception);
            throw new BusinessException(ResultCode.TOKEN_BLACKLIST_UNAVAILABLE);
        }
    }

    /**
     * 写入过滤器阶段的统一失败响应。
     *
     * @param response 当前 HTTP 响应
     * @param resultCode 业务码
     * @throws IOException 响应写入异常
     */
    private void writeFailure(HttpServletResponse response, ResultCode resultCode) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.failure(resultCode, TraceIdContext.getTraceId())));
    }
}
