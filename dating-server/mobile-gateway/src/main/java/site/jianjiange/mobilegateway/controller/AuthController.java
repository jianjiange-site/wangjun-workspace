package site.jianjiange.mobilegateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.jianjiange.mobilegateway.context.AuthContextHolder;
import site.jianjiange.mobilegateway.dto.DeviceLoginRequest;
import site.jianjiange.mobilegateway.dto.PhoneLoginRequest;
import site.jianjiange.mobilegateway.dto.RefreshTokenRequest;
import site.jianjiange.mobilegateway.dto.SendSmsCodeRequest;
import site.jianjiange.mobilegateway.service.AuthService;
import site.jianjiange.mobilegateway.service.DeviceLoginService;
import site.jianjiange.mobilegateway.service.PhoneLoginService;
import site.jianjiange.mobilegateway.service.SmsCodeService;
import site.jianjiange.mobilegateway.vo.LoginTokenVO;

/**
 * 认证接口控制器，暴露登录和 token 相关 REST API。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final DeviceLoginService deviceLoginService;
    private final PhoneLoginService phoneLoginService;
    private final SmsCodeService smsCodeService;
    private final AuthService authService;

    /**
     * 创建认证控制器。
     *
     * @param deviceLoginService 设备快速登录服务
     * @param authService 认证编排服务
     */
    public AuthController(DeviceLoginService deviceLoginService, PhoneLoginService phoneLoginService,
                          SmsCodeService smsCodeService, AuthService authService) {
        this.deviceLoginService = deviceLoginService;
        this.phoneLoginService = phoneLoginService;
        this.smsCodeService = smsCodeService;
        this.authService = authService;
    }

    /**
     * 发送手机验证码。
     *
     * @param request 发送验证码请求
     */
    @PostMapping("/sms-code")
    public void sendSmsCode(@Valid @RequestBody SendSmsCodeRequest request) {
        smsCodeService.sendCode(request.getPhone());
    }

    /**
     * 手机验证码登录或注册。
     *
     * @param request 手机登录请求
     * @return 登录 token 信息
     */
    @PostMapping("/login/phone")
    public LoginTokenVO loginByPhone(@Valid @RequestBody PhoneLoginRequest request) {
        return phoneLoginService.login(request);
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
