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
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import site.jianjiange.mobilegateway.client.UserGrpcClient;
import site.jianjiange.mobilegateway.entity.GatewayAccountEntity;
import site.jianjiange.mobilegateway.entity.GatewayRefreshTokenEntity;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.exception.BusinessException;
import site.jianjiange.mobilegateway.manager.AuthConstants;
import site.jianjiange.mobilegateway.mapper.GatewayAccountMapper;
import site.jianjiange.mobilegateway.mapper.GatewayRefreshTokenMapper;
import site.jianjiange.mobilegateway.service.JwtService;

/**
 * 设备快速登录最小闭环集成测试。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class DeviceLoginIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GatewayAccountMapper accountMapper;

    @Autowired
    private GatewayRefreshTokenMapper refreshTokenMapper;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private UserGrpcClient userGrpcClient;

    /**
     * 清理认证表并默认 mock user-service 初始化成功。
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM gateway_refresh_token");
        jdbcTemplate.update("DELETE FROM gateway_device");
        jdbcTemplate.update("DELETE FROM gateway_account");
        when(userGrpcClient.registerOrInitialize(anyLong(), anyString(), anyString()))
                .thenReturn(new UserGrpcClient.RegisterOrInitializeResult(0, "ok"));
    }

    /**
     * 验证设备登录会签发 token，同一设备保持同一 userId，不同设备创建不同用户。
     */
    @Test
    void deviceLoginIssuesTokensAndKeepsSameUserForSameDevice() throws Exception {
        JsonNode first = deviceLogin("device-key-stage2-0001");
        JsonNode second = deviceLogin("device-key-stage2-0001");
        JsonNode third = deviceLogin("device-key-stage2-0002");

        long firstUserId = first.at("/data/user_id").asLong();
        long secondUserId = second.at("/data/user_id").asLong();
        long thirdUserId = third.at("/data/user_id").asLong();

        assertThat(first.at("/code").asInt()).isZero();
        assertThat(first.at("/data/token_type").asText()).isEqualTo("Bearer");
        assertThat(first.at("/data/expires_in").asLong()).isEqualTo(900);
        assertThat(secondUserId).isEqualTo(firstUserId);
        assertThat(thirdUserId).isNotEqualTo(firstUserId);
        assertJwtSignature(first.at("/data/access_token").asText());
        String refreshTokenValue = first.at("/data/refresh_token").asText();
        String[] refreshTokenParts = refreshTokenValue.split("\\.");
        assertThat(refreshTokenParts).hasSize(2);
        assertThat(refreshTokenParts[0]).startsWith("rt_");
        assertThat(refreshTokenParts[1]).hasSize(43);

        GatewayRefreshTokenEntity refreshToken = refreshTokenMapper.selectOne(
                new LambdaQueryWrapper<GatewayRefreshTokenEntity>()
                        .eq(GatewayRefreshTokenEntity::getUserId, firstUserId)
                        .eq(GatewayRefreshTokenEntity::getStatus, AuthConstants.REFRESH_STATUS_ACTIVE)
                        .last("limit 1"));
        assertThat(refreshToken).isNotNull();
        assertThat(refreshToken.getTokenJti()).isEqualTo(refreshTokenParts[0].substring("rt_".length()));
        assertThat(refreshToken.getTokenHash()).startsWith("v1:");
        assertThat(refreshToken.getTokenHash()).doesNotContain(refreshTokenValue);
        assertThat(refreshToken.getTokenHash()).doesNotContain(refreshTokenParts[1]);
    }

    /**
     * 验证 user-service 初始化失败时账号保持待初始化，后续重试可继续完成闭环。
     */
    @Test
    void userServiceInitializeFailureKeepsAccountPendingAndAllowsRetry() throws Exception {
        when(userGrpcClient.registerOrInitialize(anyLong(), anyString(), anyString()))
                .thenThrow(new BusinessException(ResultCode.USER_REGISTER_INITIALIZE_FAILED))
                .thenReturn(new UserGrpcClient.RegisterOrInitializeResult(0, "ok"));

        mockMvc.perform(post("/api/v1/auth/login/device")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("deviceKey", "device-key-stage2-0003"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10703))
                .andExpect(jsonPath("$.data").doesNotExist());

        GatewayAccountEntity pendingAccount = accountMapper.selectOne(
                new LambdaQueryWrapper<GatewayAccountEntity>()
                        .eq(GatewayAccountEntity::getAccountType, AuthConstants.ACCOUNT_TYPE_DEVICE)
                        .eq(GatewayAccountEntity::getDeleted, AuthConstants.DELETED_NO));
        assertThat(pendingAccount).isNotNull();
        assertThat(pendingAccount.getUserRegisterStatus()).isEqualTo(AuthConstants.REGISTER_STATUS_PENDING);
        assertThat(refreshTokenMapper.selectCount(null)).isZero();

        JsonNode retry = deviceLogin("device-key-stage2-0003");
        assertThat(retry.at("/code").asInt()).isZero();

        GatewayAccountEntity initializedAccount = accountMapper.selectOne(
                new LambdaQueryWrapper<GatewayAccountEntity>()
                        .eq(GatewayAccountEntity::getAccountId, pendingAccount.getAccountId()));
        assertThat(initializedAccount.getUserId()).isEqualTo(pendingAccount.getUserId());
        assertThat(initializedAccount.getUserRegisterStatus()).isEqualTo(AuthConstants.REGISTER_STATUS_DONE);
        assertThat(refreshTokenMapper.selectCount(null)).isEqualTo(1);
    }

    /**
     * 调用设备登录接口并返回解析后的统一响应。
     *
     * @param deviceKey 测试设备唯一标识
     * @return 解析后的 JSON 响应
     * @throws Exception 请求执行或 JSON 解析失败时抛出
     */
    private JsonNode deviceLogin(String deviceKey) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login/device")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "deviceKey", deviceKey,
                                "deviceName", "test-device",
                                "clientType", "android"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    /**
     * 校验访问 token 使用 RS256 签名且可被当前公钥验签。
     *
     * @param token 待校验的 JWT
     * @throws Exception JWT 解析或签名算法执行失败时抛出
     */
    private void assertJwtSignature(String token) throws Exception {
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        JsonNode header = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertThat(header.get("alg").asText()).isEqualTo("RS256");
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(jwtService.getPublicKey());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }
}
