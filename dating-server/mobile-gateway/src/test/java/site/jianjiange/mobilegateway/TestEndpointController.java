package site.jianjiange.mobilegateway;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.exception.BusinessException;

/**
 * Web 基础设施测试使用的本地端点。
 */
@RestController
class TestEndpointController {

    /**
     * 返回普通对象，用于验证统一响应自动包装。
     *
     * @return 测试数据
     */
    @GetMapping("/test/success")
    Map<String, String> success() {
        return Map.of("value", "ok");
    }

    /**
     * 抛出业务异常，用于验证全局异常处理。
     */
    @GetMapping("/test/business")
    void business() {
        throw new BusinessException(ResultCode.UNAUTHORIZED);
    }

    /**
     * 接收带校验的请求体，用于验证参数错误映射。
     *
     * @param request 测试请求
     * @return 回显数据
     */
    @PostMapping("/test/valid")
    Map<String, String> valid(@Valid @RequestBody TestRequest request) {
        return Map.of("name", request.name());
    }

    /**
     * 测试请求体。
     *
     * @param name 必填名称
     */
    record TestRequest(@NotBlank String name) {
    }
}
