package site.jianjiange.mobilegateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import site.jianjiange.mobilegateway.config.JwtConfig;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.exception.BusinessException;

/**
 * JWT 签发和验签服务测试。
 */
@ActiveProfiles("test")
@SpringBootTest
class JwtServiceTest {

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JwtConfig jwtConfig;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 验证签发出的 access token 可被验签并还原核心 claim。
     */
    @Test
    void verifiesIssuedAccessToken() {
        JwtService.IssuedTokens issued = jwtService.issue(11L, 22L, 33L);

        JwtService.VerifiedAccessToken verified = jwtService.verifyAccessToken(issued.accessToken());

        assertThat(verified.accountId()).isEqualTo(11L);
        assertThat(verified.userId()).isEqualTo(22L);
        assertThat(verified.deviceId()).isEqualTo(33L);
        assertThat(verified.jti()).isEqualTo(issued.accessTokenJti());
    }

    /**
     * 验证已过期 access token 映射为 10501。
     */
    @Test
    void expiredAccessTokenReturnsExpiredCode() {
        JwtService expiredJwtService = new JwtService(jwtConfig(-1), objectMapper);
        expiredJwtService.loadKeys();
        String token = expiredJwtService.issue(11L, 22L, 33L).accessToken();

        assertThatThrownBy(() -> jwtService.verifyAccessToken(token))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(ResultCode.ACCESS_TOKEN_EXPIRED));
    }

    /**
     * 验证篡改 alg 为 none 会被拒绝为无效 access token。
     */
    @Test
    void noneAlgorithmAccessTokenReturnsInvalidCode() throws Exception {
        JwtService.IssuedTokens issued = jwtService.issue(11L, 22L, 33L);
        String token = withHeaderAlgorithm(issued.accessToken(), "none");

        assertThatThrownBy(() -> jwtService.verifyAccessToken(token))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(ResultCode.ACCESS_TOKEN_INVALID));
    }

    /**
     * 创建测试用 JWT 配置。
     *
     * @param accessTokenTtlSeconds access token TTL 秒数
     * @return JWT 配置
     */
    private JwtConfig jwtConfig(long accessTokenTtlSeconds) {
        JwtConfig config = new JwtConfig();
        config.setIssuer(jwtConfig.getIssuer());
        config.setAccessTokenTtlSeconds(accessTokenTtlSeconds);
        config.setRefreshTokenTtlSeconds(jwtConfig.getRefreshTokenTtlSeconds());
        config.setPrivateKey(jwtConfig.getPrivateKey());
        config.setPublicKey(jwtConfig.getPublicKey());
        return config;
    }

    /**
     * 替换 JWT header alg，不重签名，用于验证错误算法被前置拒绝。
     *
     * @param token 原始 JWT
     * @param algorithm 新算法
     * @return 篡改 header 后的 JWT
     * @throws Exception JSON 处理异常
     */
    private String withHeaderAlgorithm(String token, String algorithm) throws Exception {
        String[] parts = token.split("\\.");
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", algorithm);
        header.put("typ", "JWT");
        String headerPart = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(header));
        return headerPart + "." + parts[1] + "." + parts[2];
    }
}
