package site.jianjiange.mobilegateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.jianjiange.mobilegateway.context.AuthContextHolder;
import site.jianjiange.mobilegateway.dto.DeviceLoginRequest;
import site.jianjiange.mobilegateway.dto.RefreshTokenRequest;
import site.jianjiange.mobilegateway.service.AuthService;
import site.jianjiange.mobilegateway.service.DeviceLoginService;
import site.jianjiange.mobilegateway.vo.LoginTokenVO;

/**
 * 认证接口控制器，暴露登录和 token 相关 REST API。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final DeviceLoginService deviceLoginService;
    private final AuthService authService;

    /**
     * 创建认证控制器。
     *
     * @param deviceLoginService 设备快速登录服务
     * @param authService 认证编排服务
     */
    public AuthController(DeviceLoginService deviceLoginService, AuthService authService) {
        this.deviceLoginService = deviceLoginService;
        this.authService = authService;
    }

    /**
     * 设备号快速登录或注册。
     *
     * @param request 设备登录请求
     * @param servletRequest 当前 HTTP 请求
     * @return 登录 token 信息
     */
    @PostMapping("/login/device")
    public LoginTokenVO loginByDevice(@Valid @RequestBody DeviceLoginRequest request,
                                      HttpServletRequest servletRequest) {
        return deviceLoginService.login(request, servletRequest);
    }

    /**
     * 使用 refresh token 刷新当前登录 token。
     *
     * @param request refresh token 请求体
     * @return 新 token 信息
     */
    @PostMapping("/refresh")
    public LoginTokenVO refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request.getRefreshToken());
    }

    /**
     * 退出当前登录会话。
     */
    @PostMapping("/logout")
    public void logout() {
        authService.logout(AuthContextHolder.get());
    }
}
