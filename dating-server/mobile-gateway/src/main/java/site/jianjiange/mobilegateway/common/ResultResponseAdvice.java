package site.jianjiange.mobilegateway.common;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import site.jianjiange.mobilegateway.support.TraceIdContext;

/**
 * Controller 响应统一包装切面。
 */
@RestControllerAdvice(basePackages = "site.jianjiange.mobilegateway")
public class ResultResponseAdvice implements ResponseBodyAdvice<Object> {

    /**
     * 判断当前返回值是否需要自动包装为统一 Result 结构。
     *
     * @param returnType Controller 方法返回类型
     * @param converterType HTTP 消息转换器类型
     * @return 非字符串响应返回 true
     */
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return !StringHttpMessageConverter.class.isAssignableFrom(converterType);
    }

    /**
     * 将普通 Controller 返回值包装成 Result，并补入当前 traceId。
     *
     * @param body 原始响应体
     * @param returnType Controller 方法返回类型
     * @param selectedContentType 选中的响应类型
     * @param selectedConverterType 选中的消息转换器
     * @param request 当前请求
     * @param response 当前响应
     * @return 统一响应体
     */
    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof Result<?>) {
            return body;
        }
        return Result.success(body, TraceIdContext.getTraceId());
    }
}
