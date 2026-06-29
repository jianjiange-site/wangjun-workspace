package site.jianjiange.mobilegateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.grpc.Metadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import site.jianjiange.mobilegateway.client.UserGrpcClient;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.exception.BusinessException;
import site.jianjiange.mobilegateway.grpc.user.GetCurrentUserRequest;
import site.jianjiange.mobilegateway.grpc.user.GetCurrentUserResponse;
import site.jianjiange.mobilegateway.service.JwtService;

/**
 * /api/v1/users/me REST/gRPC 转发集成测试。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class UserProxyIntegrationTest {

    private static final Metadata.Key<String> TRACE_ID_METADATA_KEY =
            Metadata.Key.of("trace_id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> USER_ID_METADATA_KEY =
            Metadata.Key.of("user_id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> ACCOUNT_ID_METADATA_KEY =
            Metadata.Key.of("account_id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> DEVICE_ID_METADATA_KEY =
            Metadata.Key.of("device_id", Metadata.ASCII_STRING_MARSHALLER);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private UserGrpcClient userGrpcClient;

    @MockBean
    private StringRedisTemplate redisTemplate;

    /**
     * 初始化 Redis blacklist 和 user-service mock。
     */
    @BeforeEach
    void setUp() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(userGrpcClient.getCurrentUser(any(GetCurrentUserRequest.class), any(Metadata.class)))
                .thenReturn(GetCurrentUserResponse.newBuilder()
                        .setUserId(1001L)
                        .setNickname("Alice")
                        .setAvatarUrl("https://cdn.example.com/a.png")
                        .setBio("hello")
                        .build());
    }

    /**
     * 验证缺失 token 时受保护转发接口返回未登录。
     */
    @Test
    void usersMeRequiresAccessToken() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10100));
    }

    /**
     * 验证合法 token 可调用 user-service mock，并返回当前用户资料。
     */
    @Test
    void usersMeReturnsCurrentUserAndPassesMetadata() throws Exception {
        String accessToken = jwtService.issue(2002L, 1001L, 3003L).accessToken();

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .header("X-Trace-Id", "trace-stage7-http"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.traceId").value("trace-stage7-http"))
                .andExpect(jsonPath("$.data.user_id").value(1001))
                .andExpect(jsonPath("$.data.nickname").value("Alice"))
                .andExpect(jsonPath("$.data.avatar_url").value("https://cdn.example.com/a.png"))
                .andExpect(jsonPath("$.data.bio").value("hello"));

        ArgumentCaptor<GetCurrentUserRequest> requestCaptor = ArgumentCaptor.forClass(GetCurrentUserRequest.class);
        ArgumentCaptor<Metadata> metadataCaptor = ArgumentCaptor.forClass(Metadata.class);
        verify(userGrpcClient).getCurrentUser(requestCaptor.capture(), metadataCaptor.capture());
        assertThat(requestCaptor.getValue().getUserId()).isEqualTo(1001L);
        assertThat(requestCaptor.getValue().getTraceId()).isEqualTo("trace-stage7-http");
        assertThat(metadataCaptor.getValue().get(TRACE_ID_METADATA_KEY)).isEqualTo("trace-stage7-http");
        assertThat(metadataCaptor.getValue().get(USER_ID_METADATA_KEY)).isEqualTo("1001");
        assertThat(metadataCaptor.getValue().get(ACCOUNT_ID_METADATA_KEY)).isEqualTo("2002");
        assertThat(metadataCaptor.getValue().get(DEVICE_ID_METADATA_KEY)).isEqualTo("3003");
    }

    /**
     * 验证下游超时映射为 10701，响应中保留 traceId。
     */
    @Test
    void grpcTimeoutReturnsConfiguredBusinessCode() throws Exception {
        when(userGrpcClient.getCurrentUser(any(GetCurrentUserRequest.class), any(Metadata.class)))
                .thenThrow(new BusinessException(ResultCode.DOWNSTREAM_GRPC_TIMEOUT));
        String accessToken = jwtService.issue(2002L, 1001L, 3003L).accessToken();

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .header("X-Trace-Id", "trace-stage7-timeout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10701))
                .andExpect(jsonPath("$.traceId").value("trace-stage7-timeout"));
    }

    /**
     * 验证普通 gRPC 异常映射为 10702。
     */
    @Test
    void grpcErrorReturnsConfiguredBusinessCode() throws Exception {
        when(userGrpcClient.getCurrentUser(any(GetCurrentUserRequest.class), any(Metadata.class)))
                .thenThrow(new BusinessException(ResultCode.DOWNSTREAM_GRPC_ERROR));
        String accessToken = jwtService.issue(2002L, 1001L, 3003L).accessToken();

        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10702));
    }
}
