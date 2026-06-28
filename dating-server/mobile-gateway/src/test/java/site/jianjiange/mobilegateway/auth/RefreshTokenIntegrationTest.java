package site.jianjiange.mobilegateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import site.jianjiange.mobilegateway.client.UserGrpcClient;
import site.jianjiange.mobilegateway.entity.GatewayRefreshTokenEntity;
import site.jianjiange.mobilegateway.manager.AuthConstants;
import site.jianjiange.mobilegateway.mapper.GatewayRefreshTokenMapper;

/**
 * refresh token 轮换闭环集成测试。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class RefreshTokenIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GatewayRefreshTokenMapper refreshTokenMapper;

    @MockBean
    private UserGrpcClient userGrpcClient;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    /**
     * 清理认证数据并初始化外部依赖 mock。
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM gateway_refresh_token");
        jdbcTemplate.update("DELETE FROM gateway_device");
        jdbcTemplate.update("DELETE FROM gateway_account");
        when(userGrpcClient.registerOrInitialize(anyLong(), anyString(), anyString()))
                .thenReturn(new UserGrpcClient.RegisterOrInitializeResult(0, "ok"));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
    }

    /**
     * 验证合法 refresh token 会返回新 token，旧 token 只能使用一次。
     */
    @Test
    void refreshRotatesOldTokenAndIssuesNewTokens() throws Exception {
        JsonNode login = deviceLogin("device-key-stage4-refresh");
        String oldRefreshToken = login.at("/data/refresh_token").asText();

        JsonNode refreshed = refresh(oldRefreshToken);

        assertThat(refreshed.at("/code").asInt()).isZero();
        assertThat(refreshed.at("/data/token_type").asText()).isEqualTo("Bearer");
        assertThat(refreshed.at("/data/access_token").asText()).isNotEqualTo(login.at("/data/access_token").asText());
        assertThat(refreshed.at("/data/refresh_token").asText()).isNotEqualTo(oldRefreshToken);
        assertThat(refreshed.at("/data/user_id").asLong()).isEqualTo(login.at("/data/user_id").asLong());

        String oldJti = parseRefreshJti(oldRefreshToken);
        GatewayRefreshTokenEntity oldToken = refreshTokenMapper.selectOne(
                new LambdaQueryWrapper<GatewayRefreshTokenEntity>()
                        .eq(GatewayRefreshTokenEntity::getTokenJti, oldJti)
                        .last("limit 1"));
        assertThat(oldToken.getStatus()).isEqualTo(AuthConstants.REFRESH_STATUS_ROTATED);

        String newRefreshToken = refreshed.at("/data/refresh_token").asText();
        GatewayRefreshTokenEntity newToken = refreshTokenMapper.selectOne(
                new LambdaQueryWrapper<GatewayRefreshTokenEntity>()
                        .eq(GatewayRefreshTokenEntity::getTokenJti, parseRefreshJti(newRefreshToken))
                        .last("limit 1"));
        assertThat(newToken.getStatus()).isEqualTo(AuthConstants.REFRESH_STATUS_ACTIVE);
        assertThat(newToken.getTokenHash()).doesNotContain(newRefreshToken);
        assertThat(newToken.getTokenHash()).doesNotContain(newRefreshToken.split("\\.")[1]);

        refreshExpectCode(oldRefreshToken, 10506);
    }

    /**
     * 验证非法格式和 hash 不匹配返回 refresh token 无效。
     */
    @Test
    void invalidOrTamperedRefreshTokenReturnsInvalidCode() throws Exception {
        refreshExpectCode("not-a-refresh-token", 10503);

        JsonNode login = deviceLogin("device-key-stage4-tampered");
        String refreshToken = login.at("/data/refresh_token").asText();
        refreshExpectCode(refreshToken + "x", 10503);
    }

    /**
     * 验证过期 refresh token 返回过期业务码。
     */
    @Test
    void expiredRefreshTokenReturnsExpiredCode() throws Exception {
        JsonNode login = deviceLogin("device-key-stage4-expired");
        String refreshToken = login.at("/data/refresh_token").asText();
        jdbcTemplate.update("UPDATE gateway_refresh_token SET expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) "
                + "WHERE token_jti = ?", parseRefreshJti(refreshToken));

        refreshExpectCode(refreshToken, 10504);
    }

    /**
     * 验证已撤销 refresh token 返回撤销业务码。
     */
    @Test
    void revokedRefreshTokenReturnsRevokedCode() throws Exception {
        JsonNode login = deviceLogin("device-key-stage4-revoked");
        String refreshToken = login.at("/data/refresh_token").asText();
        jdbcTemplate.update("UPDATE gateway_refresh_token SET status = ? WHERE token_jti = ?",
                AuthConstants.REFRESH_STATUS_REVOKED, parseRefreshJti(refreshToken));

        refreshExpectCode(refreshToken, 10505);
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

    /**
     * 调用 refresh 接口。
     *
     * @param refreshToken refresh token 明文
     * @return refresh 响应 JSON
     * @throws Exception 请求或解析失败时抛出
     */
    private JsonNode refresh(String refreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refresh_token", refreshToken))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    /**
     * 断言 refresh 接口返回指定业务码。
     *
     * @param refreshToken refresh token 明文
     * @param code 期望业务码
     * @throws Exception 请求失败时抛出
     */
    private void refreshExpectCode(String refreshToken, int code) throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refresh_token", refreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /**
     * 从 refresh token 明文中提取 jti。
     *
     * @param refreshToken refresh token 明文
     * @return jti
     */
    private String parseRefreshJti(String refreshToken) {
        return refreshToken.split("\\.")[0].substring("rt_".length());
    }
}
