package site.jianjiange.mobilegateway.service;

import org.springframework.stereotype.Service;
import site.jianjiange.mobilegateway.grpc.adapter.UserGrpcAdapter;
import site.jianjiange.mobilegateway.vo.CurrentUserVO;

/**
 * 网关 REST/gRPC 转发服务。
 */
@Service
public class GatewayProxyService {

    private final UserGrpcAdapter userGrpcAdapter;

    /**
     * 创建网关转发服务。
     *
     * @param userGrpcAdapter user-service gRPC adapter
     */
    public GatewayProxyService(UserGrpcAdapter userGrpcAdapter) {
        this.userGrpcAdapter = userGrpcAdapter;
    }

    /**
     * 查询当前用户资料。
     *
     * @return 当前用户资料
     */
    public CurrentUserVO getCurrentUser() {
        return userGrpcAdapter.getCurrentUser();
    }
}
