package site.jianjiange.mobilegateway.web;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import site.jianjiange.mobilegateway.support.TraceIdContext;

/**
 * 网关统一响应、异常处理、traceId 和 CORS 基础设施测试。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class GatewayWebInfrastructureTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * 验证成功响应会被包装并生成 traceId。
     */
    @Test
    void successResponseIsWrappedAndTraceIdIsGenerated() throws Exception {
        mockMvc.perform(get("/test/success"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdContext.TRACE_ID_HEADER, not(blankOrNullString())))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.value").value("ok"))
                .andExpect(jsonPath("$.traceId", not(blankOrNullString())));
    }

    /**
     * 验证客户端传入的 traceId 会透传到响应头和响应体。
     */
    @Test
    void incomingTraceIdIsPropagated() throws Exception {
        mockMvc.perform(get("/test/success").header(TraceIdContext.TRACE_ID_HEADER, "trace-from-client"))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdContext.TRACE_ID_HEADER, "trace-from-client"))
                .andExpect(jsonPath("$.traceId").value("trace-from-client"));
    }

    /**
     * 验证业务异常会使用登记的业务码。
     */
    @Test
    void businessExceptionUsesRegisteredCode() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10100))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /**
     * 验证参数校验失败会返回参数错误业务码。
     */
    @Test
    void invalidRequestUsesParameterErrorCode() throws Exception {
        mockMvc.perform(post("/test/valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10001));
    }

    /**
     * 验证 CORS 预检请求使用配置的来源和暴露响应头。
     */
    @Test
    void corsPreflightUsesConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/test/success")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Expose-Headers", TraceIdContext.TRACE_ID_HEADER));
    }
}
