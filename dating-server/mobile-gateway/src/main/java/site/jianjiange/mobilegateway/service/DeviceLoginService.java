package site.jianjiange.mobilegateway.service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import site.jianjiange.mobilegateway.config.HashConfig;
import site.jianjiange.mobilegateway.config.RateLimitConfig;
import site.jianjiange.mobilegateway.dto.DeviceLoginRequest;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.exception.BusinessException;
import site.jianjiange.mobilegateway.support.HmacHasher;
import site.jianjiange.mobilegateway.vo.LoginTokenVO;

/**
 * 设备号快速登录服务，负责设备凭据校验、限流和认证闭环编排。
 */
@Service
public class DeviceLoginService {

    private static final Pattern DEVICE_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");

    private final HmacHasher hmacHasher;
    private final HashConfig hashConfig;
    private final RateLimitConfig rateLimitConfig;
    private final RateLimitService rateLimitService;
    private final AuthService authService;

    /**
     * 创建设备号快速登录服务。
     *
     * @param hmacHasher HMAC hash 工具
     * @param hashConfig HMAC 配置
     * @param rateLimitConfig 限流配置
     * @param rateLimitService 限流服务
     * @param authService 认证编排服务
     */
    public DeviceLoginService(HmacHasher hmacHasher, HashConfig hashConfig, RateLimitConfig rateLimitConfig,
                              RateLimitService rateLimitService, AuthService authService) {
        this.hmacHasher = hmacHasher;
        this.hashConfig = hashConfig;
        this.rateLimitConfig = rateLimitConfig;
        this.rateLimitService = rateLimitService;
        this.authService = authService;
    }

    /**
     * 执行设备号快速登录。
     *
     * @param request 设备登录请求
     * @param servletRequest 当前 HTTP 请求
     * @return 登录 token 响应
     */
    public LoginTokenVO login(DeviceLoginRequest request, HttpServletRequest servletRequest) {
        String deviceKey = normalizeDeviceKey(request.getDeviceKey());
        String deviceKeyHash = hmacHasher.hashDevice(deviceKey);
        if (rateLimitConfig.isEnabled()
                && !rateLimitService.isAllowed("device-login", clientIp(servletRequest) + ":" + deviceKeyHash)) {
            throw new BusinessException(ResultCode.RATE_LIMITED);
        }
        AuthService.LoginIdentity identity = findOrCreateIdentity(deviceKeyHash, request);
        authService.ensureUserInitialized(identity);
        return authService.issueLoginTokens(identity);
    }

    /**
     * 查找或创建设备登录身份，并在唯一键并发冲突时回读已落库身份。
     *
     * @param deviceKeyHash 设备标识 HMAC hash
     * @param request 设备登录请求
     * @return 登录身份信息
     */
    private AuthService.LoginIdentity findOrCreateIdentity(String deviceKeyHash, DeviceLoginRequest request) {
        try {
            return authService.findOrCreateDeviceIdentity(deviceKeyHash, hashConfig.getVersion(),
                    request.getDeviceName(), request.getClientType());
        } catch (DuplicateKeyException exception) {
            return authService.loadDeviceIdentityAfterConflict(deviceKeyHash);
        }
    }

    /**
     * 规范化并校验设备唯一键，确保只接受约定长度和字符集的设备标识。
     *
     * @param deviceKey 原始设备唯一键
     * @return 去除首尾空白后的设备唯一键
     */
    private String normalizeDeviceKey(String deviceKey) {
        if (!StringUtils.hasText(deviceKey)) {
            throw new BusinessException(ResultCode.DEVICE_INVALID);
        }
        String normalized = deviceKey.trim();
        if (!DEVICE_KEY_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ResultCode.DEVICE_INVALID);
        }
        return normalized;
    }

    /**
     * 提取客户端 IP，优先采用代理转发头中的第一个地址。
     *
     * @param request 当前 HTTP 请求
     * @return 客户端 IP
     */
    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
