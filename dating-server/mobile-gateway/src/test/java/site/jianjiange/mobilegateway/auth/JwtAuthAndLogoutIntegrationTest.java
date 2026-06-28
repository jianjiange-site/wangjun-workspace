package site.jianjiange.mobilegateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import site.jianjiange.mobilegateway.client.UserGrpcClient;
import site.jianjiange.mobilegateway.entity.GatewayRefreshTokenEntity;
import site.jianjiange.mobilegateway.manager.AuthConstants;
import site.jianjiange.mobilegateway.mapper.GatewayRefreshTokenMapper;
import site.jianjiange.mobilegateway.service.JwtService;

/**
 * JWT 鉴权、认证上下文和 logout blacklist 集成测试。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class JwtAuthAndLogoutIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GatewayRefreshTokenMapper refreshTokenMapper;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private UserGrpcClient userGrpcClient;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    private final AtomicBoolean blacklisted = new AtomicBoolean(false);
    private final AtomicReference<String> blacklistKey = new AtomicReference<>();
    private final AtomicReference<Duration> blacklistTtl = new AtomicReference<>();

    /**
     * 清理认证数据并初始化 Redis mock。
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM gateway_refresh_token");
        jdbcTemplate.update("DELETE FROM gateway_device");
        jdbcTemplate.update("DELETE FROM gateway_account");
        blacklisted.set(false);
        blacklistKey.set(null);
        blacklistTtl.set(null);
        when(userGrpcClient.registerOrInitialize(anyLong(), anyString(), anyString()))
                .thenReturn(new UserGrpcClient.RegisterOrInitializeResult(0, "ok"));
        when(redisTemplate.hasKey(anyString())).thenAnswer(invocation -> blacklisted.get());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (key.contains(":jwt:blacklist:")) {
                blacklistKey.set(key);
                blacklistTtl.set(invocation.getArgument(2));
                blacklisted.set(true);
            }
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
    }

    /**
     * 验证受保护 API 缺失 Bearer token 时返回未登录。
     */
    @Test
    void protectedApiRequiresBearerToken() throws Exception {
        mockMvc.perform(get("/api/v1/test/protected"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10100));
    }

    /**
     * 验证有效 access token 可访问受保护 API，并注入认证上下文。
     */
    @Test
    void validAccessTokenInjectsAuthContext() throws Exception {
        JsonNode login = deviceLogin("device-key-stage3-ctx");
        String accessToken = login.at("/data/access_token").asText();
        JwtService.VerifiedAccessToken verified = jwtService.verifyAccessToken(accessToken);

        mockMvc.perform(get("/api/v1/test/protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .header("X-Trace-Id", "trace-stage3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.user_id").value(verified.userId()))
                .andExpect(jsonPath("$.data.account_id").value(verified.accountId()))
                .andExpect(jsonPath("$.data.device_id").value(verified.deviceId()))
                .andExpect(jsonPath("$.data.trace_id").value("trace-stage3"));
    }

    /**
     * 验证非法 token 映射为 access token 无效。
     */
    @Test
    void invalidAccessTokenReturnsInvalidCode() throws Exception {
        mockMvc.perform(get("/api/v1/test/protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.token.value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10500));
    }

    /**
     * 验证 Redis blacklist 查询失败时 fail closed。
     */
    @Test
    void blacklistLookupFailureReturnsUnavailableCode() throws Exception {
        JsonNode login = deviceLogin("device-key-stage3-redis-down");
        String accessToken = login.at("/data/access_token").asText();
        when(redisTemplate.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));

        mockMvc.perform(get("/api/v1/test/protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10508));
    }

    /**
     * 验证 logout 写入 blacklist，撤销 refresh token，并拒绝同一个 access token 继续访问。
     */
    @Test
    void logoutBlacklistsAccessTokenAndRevokesRefreshSession() throws Exception {
        JsonNode login = deviceLogin("device-key-stage3-logout");
        String accessToken = login.at("/data/access_token").asText();
        JwtService.VerifiedAccessToken verified = jwtService.verifyAccessToken(accessToken);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        assertThat(blacklistKey.get()).isEqualTo("dating:mobile-gateway-test:jwt:blacklist:" + verified.jti());
        assertThat(blacklistTtl.get()).isNotNull();
        assertThat(blacklistTtl.get().getSeconds()).isPositive();

        GatewayRefreshTokenEntity refreshToken = refreshTokenMapper.selectOne(
                new LambdaQueryWrapper<GatewayRefreshTokenEntity>()
                        .eq(GatewayRefreshTokenEntity::getUserId, verified.userId())
                        .last("limit 1"));
        assertThat(refreshToken.getStatus()).isEqualTo(AuthConstants.REFRESH_STATUS_REVOKED);

        mockMvc.perform(get("/api/v1/test/protected")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10502));
    }

    /**
     * 调用设备登录接口。
     *
     * @param deviceKey 设备唯一标识
     * @return 登录响应 JSON
     * @throws Exception 请求或解析失败时抛出
     */
    private JsonNode deviceLogin(String deviceKey) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login/device")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "deviceKey", deviceKey,
                                "deviceName", "test-device",
                                "clientType", "android"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
