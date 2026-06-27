package site.jianjiange.mobilegateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.jianjiange.mobilegateway.dto.DeviceLoginRequest;
import site.jianjiange.mobilegateway.service.DeviceLoginService;
import site.jianjiange.mobilegateway.vo.LoginTokenVO;

/**
 * 认证接口控制器，暴露登录和 token 相关 REST API。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final DeviceLoginService deviceLoginService;

    /**
     * 创建认证控制器。
     *
     * @param deviceLoginService 设备快速登录服务
     */
    public AuthController(DeviceLoginService deviceLoginService) {
        this.deviceLoginService = deviceLoginService;
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
}
