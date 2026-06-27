package site.jianjiange.mobilegateway.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import site.jianjiange.mobilegateway.service.RateLimitService;

/**
 * 入口限流过滤器 Web 层行为测试。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "gateway.rate-limit.enabled=true")
class RateLimitFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RateLimitService rateLimitService;

    /**
     * 验证触发限流时返回 10600 业务码。
     */
    @Test
    void rateLimitedRequestReturnsBusinessCode() throws Exception {
        when(rateLimitService.isAllowed(anyString(), anyString())).thenReturn(false);

        mockMvc.perform(get("/test/success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10600));
    }
}
