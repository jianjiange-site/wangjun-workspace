package site.jianjiange.mobilegateway.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.jianjiange.mobilegateway.service.GatewayProxyService;
import site.jianjiange.mobilegateway.vo.CurrentUserVO;

/**
 * 网关 REST/gRPC 转发控制器。
 */
@RestController
@RequestMapping("/api/v1")
public class GatewayProxyController {

    private final GatewayProxyService gatewayProxyService;

    /**
     * 创建网关转发控制器。
     *
     * @param gatewayProxyService 网关转发服务
     */
    public GatewayProxyController(GatewayProxyService gatewayProxyService) {
        this.gatewayProxyService = gatewayProxyService;
    }

    /**
     * 获取当前登录用户资料。
     *
     * @return 当前用户资料
     */
    @GetMapping("/users/me")
    public CurrentUserVO getCurrentUser() {
        return gatewayProxyService.getCurrentUser();
    }
}
