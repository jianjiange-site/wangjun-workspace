package site.jianjiange.mobilegateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import site.jianjiange.mobilegateway.client.UserGrpcClient;
import site.jianjiange.mobilegateway.entity.GatewayAccountEntity;
import site.jianjiange.mobilegateway.entity.GatewayRefreshTokenEntity;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.manager.AuthConstants;
import site.jianjiange.mobilegateway.mapper.GatewayAccountMapper;
import site.jianjiange.mobilegateway.mapper.GatewayRefreshTokenMapper;
import site.jianjiange.mobilegateway.support.HmacHasher;

/**
 * Google 登录闭环集成测试。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class GoogleLoginIntegrationTest {

    private static final String CLIENT_ID = "test-google-client-id";
    private static final String SUBJECT = "google-subject-stage6";
    private static final ObjectMapper STATIC_OBJECT_MAPPER = new ObjectMapper();
    private static final AtomicReference<String> JWKS_BODY = new AtomicReference<>();
    private static final AtomicInteger JWKS_STATUS = new AtomicInteger(200);
    private static final AtomicInteger JWKS_HITS = new AtomicInteger();
    private static final AtomicInteger TOKEN_SEQUENCE = new AtomicInteger();
    private static final KeyPair GOOGLE_KEY_PAIR = createRsaKeyPair();
    private static HttpServer jwksServer;

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
    private HmacHasher hmacHasher;

    @MockBean
    private UserGrpcClient userGrpcClient;

    /**
     * 为当前测试类启动本地 JWKS server，并动态覆盖 Google 配置。
     *
     * @param registry Spring 动态配置注册器
     */
    @DynamicPropertySource
    static void googleProperties(DynamicPropertyRegistry registry) {
        startJwksServer();
        registry.add("gateway.google.client-id", () -> CLIENT_ID);
        registry.add("gateway.google.jwks-uri",
                () -> "http://127.0.0.1:" + jwksServer.getAddress().getPort() + "/oauth2/v3/certs");
    }

    /**
     * 清理认证表并初始化外部依赖 mock。
     */
    @BeforeEach
    void setUp() {
        JWKS_STATUS.set(200);
        JWKS_HITS.set(0);
        jdbcTemplate.update("DELETE FROM gateway_refresh_token");
        jdbcTemplate.update("DELETE FROM gateway_device");
        jdbcTemplate.update("DELETE FROM gateway_account");
        when(userGrpcClient.registerOrInitialize(anyLong(), anyString(), anyString()))
                .thenReturn(new UserGrpcClient.RegisterOrInitializeResult(0, "ok"));
    }

    /**
     * 停止本地 JWKS server。
     */
    @AfterAll
    static void stopJwksServer() {
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
    }

    /**
     * 验证合法 Google token 会创建 GOOGLE 账号，且同一 subject 再次登录复用同一 user_id。
     */
    @Test
    void validGoogleTokenCreatesAccountIssuesTokensAndReusesUser() throws Exception {
        String kid = nextKid();
        JWKS_BODY.set(jwks(kid));
        JsonNode firstLogin = googleLogin(signedGoogleToken(kid, SUBJECT, CLIENT_ID,
                "https://accounts.google.com", Instant.now().plusSeconds(300), GOOGLE_KEY_PAIR));
        JsonNode secondLogin = googleLogin(signedGoogleToken(kid, SUBJECT, CLIENT_ID,
                "https://accounts.google.com", Instant.now().plusSeconds(300), GOOGLE_KEY_PAIR));
        String subjectHash = hmacHasher.hashGoogleSubject(SUBJECT);

        assertThat(firstLogin.at("/code").asInt()).isZero();
        assertThat(firstLogin.at("/data/token_type").asText()).isEqualTo("Bearer");
        assertThat(secondLogin.at("/data/user_id").asLong()).isEqualTo(firstLogin.at("/data/user_id").asLong());
        assertThat(JWKS_HITS.get()).isEqualTo(1);

        GatewayAccountEntity account = accountMapper.selectOne(new LambdaQueryWrapper<GatewayAccountEntity>()
                .eq(GatewayAccountEntity::getAccountType, AuthConstants.ACCOUNT_TYPE_GOOGLE)
                .eq(GatewayAccountEntity::getAccountKeyHash, subjectHash)
                .last("limit 1"));
        assertThat(account).isNotNull();
        assertThat(account.getUserRegisterStatus()).isEqualTo(AuthConstants.REGISTER_STATUS_DONE);
        assertThat(account.getAccountKeyHash()).doesNotContain(SUBJECT);
        assertThat(accountMapper.selectCount(new LambdaQueryWrapper<GatewayAccountEntity>()
                .eq(GatewayAccountEntity::getAccountType, AuthConstants.ACCOUNT_TYPE_GOOGLE)
                .eq(GatewayAccountEntity::getAccountKeyHash, subjectHash))).isEqualTo(1);
        assertThat(refreshTokenMapper.selectCount(new LambdaQueryWrapper<GatewayRefreshTokenEntity>()
                .eq(GatewayRefreshTokenEntity::getUserId, account.getUserId()))).isEqualTo(2);
        verify(userGrpcClient, times(1)).registerOrInitialize(anyLong(),
                eq(AuthConstants.ACCOUNT_TYPE_GOOGLE), eq(AuthConstants.REGISTER_SOURCE_GOOGLE_LOGIN));
    }

    /**
     * 验证过期 Google token 返回 10301 且不创建账号。
     */
    @Test
    void expiredGoogleTokenReturns10301AndDoesNotCreateAccount() throws Exception {
        String kid = nextKid();
        JWKS_BODY.set(jwks(kid));

        googleLoginExpectCode(signedGoogleToken(kid, SUBJECT, CLIENT_ID,
                "https://accounts.google.com", Instant.now().minusSeconds(1), GOOGLE_KEY_PAIR),
                ResultCode.GOOGLE_TOKEN_EXPIRED.getCode());

        assertThat(accountMapper.selectCount(null)).isZero();
        assertThat(refreshTokenMapper.selectCount(null)).isZero();
    }

    /**
     * 验证 audience 不匹配返回 10302 且不创建账号。
     */
    @Test
    void audienceMismatchReturns10302AndDoesNotCreateAccount() throws Exception {
        String kid = nextKid();
        JWKS_BODY.set(jwks(kid));

        googleLoginExpectCode(signedGoogleToken(kid, SUBJECT, "other-client-id",
                "https://accounts.google.com", Instant.now().plusSeconds(300), GOOGLE_KEY_PAIR),
                ResultCode.GOOGLE_AUDIENCE_MISMATCH.getCode());

        assertThat(accountMapper.selectCount(null)).isZero();
        assertThat(refreshTokenMapper.selectCount(null)).isZero();
    }

    /**
     * 验证签名错误返回 10300 且不创建账号。
     */
    @Test
    void invalidSignatureReturns10300AndDoesNotCreateAccount() throws Exception {
        String kid = nextKid();
        JWKS_BODY.set(jwks(kid));
        KeyPair otherKeyPair = createRsaKeyPair();

        googleLoginExpectCode(signedGoogleToken(kid, SUBJECT, CLIENT_ID,
                "https://accounts.google.com", Instant.now().plusSeconds(300), otherKeyPair),
                ResultCode.GOOGLE_TOKEN_INVALID.getCode());

        assertThat(accountMapper.selectCount(null)).isZero();
        assertThat(refreshTokenMapper.selectCount(null)).isZero();
    }

    /**
     * 验证 issuer 不合法返回 10300 且不创建账号。
     */
    @Test
    void invalidIssuerReturns10300AndDoesNotCreateAccount() throws Exception {
        String kid = nextKid();
        JWKS_BODY.set(jwks(kid));

        googleLoginExpectCode(signedGoogleToken(kid, SUBJECT, CLIENT_ID,
                "https://evil.example", Instant.now().plusSeconds(300), GOOGLE_KEY_PAIR),
                ResultCode.GOOGLE_TOKEN_INVALID.getCode());

        assertThat(accountMapper.selectCount(null)).isZero();
        assertThat(refreshTokenMapper.selectCount(null)).isZero();
    }

    /**
     * 验证 JWKS 不可用时 fail closed，返回 10300 且不创建账号。
     */
    @Test
    void jwksUnavailableReturns10300AndDoesNotCreateAccount() throws Exception {
        String kid = nextKid();
        JWKS_BODY.set(jwks(kid));
        JWKS_STATUS.set(500);

        googleLoginExpectCode(signedGoogleToken(kid, SUBJECT, CLIENT_ID,
                "https://accounts.google.com", Instant.now().plusSeconds(300), GOOGLE_KEY_PAIR),
                ResultCode.GOOGLE_TOKEN_INVALID.getCode());

        assertThat(accountMapper.selectCount(null)).isZero();
        assertThat(refreshTokenMapper.selectCount(null)).isZero();
    }

    /**
     * 调用 Google 登录接口。
     *
     * @param token Google ID token
     * @return 解析后的统一响应
     * @throws Exception 请求执行或 JSON 解析失败时抛出
     */
    private JsonNode googleLogin(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("google_id_token", token))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    /**
     * 调用 Google 登录接口并断言业务码。
     *
     * @param token Google ID token
     * @param code 期望业务码
     * @throws Exception 请求失败时抛出
     */
    private void googleLoginExpectCode(String token, int code) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("google_id_token", token))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /**
     * 生成测试 token 使用的唯一 kid，避免跨测试缓存互相影响。
     *
     * @return kid
     */
    private static String nextKid() {
        return "google-test-kid-" + TOKEN_SEQUENCE.incrementAndGet();
    }

    /**
     * 启动本地 JWKS server。
     */
    private static void startJwksServer() {
        if (jwksServer != null) {
            return;
        }
        try {
            jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            jwksServer.createContext("/oauth2/v3/certs", exchange -> {
                JWKS_HITS.incrementAndGet();
                byte[] body = JWKS_BODY.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(JWKS_STATUS.get(), body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            jwksServer.start();
        } catch (Exception exception) {
            throw new IllegalStateException("start jwks test server failed", exception);
        }
    }

    /**
     * 构造测试 JWKS。
     *
     * @param kid JWK kid
     * @return JWKS JSON
     */
    private static String jwks(String kid) {
        RSAPublicKey publicKey = (RSAPublicKey) GOOGLE_KEY_PAIR.getPublic();
        return """
                {"keys":[{"kty":"RSA","alg":"RS256","use":"sig","kid":"%s","n":"%s","e":"%s"}]}
                """.formatted(kid, base64Url(publicKey.getModulus()), base64Url(publicKey.getPublicExponent()));
    }

    /**
     * 签发测试用 Google ID token。
     *
     * @param kid JWT header kid
     * @param subject Google subject
     * @param audience audience
     * @param issuer issuer
     * @param expiresAt 过期时间
     * @param keyPair 签名密钥
     * @return Google ID token
     */
    private static String signedGoogleToken(String kid, String subject, String audience, String issuer,
                                            Instant expiresAt, KeyPair keyPair) throws Exception {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", kid);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("aud", audience);
        claims.put("sub", subject);
        claims.put("iat", Instant.now().minusSeconds(10).getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        String headerPart = base64Url(STATIC_OBJECT_MAPPER.writeValueAsBytes(header));
        String payloadPart = base64Url(STATIC_OBJECT_MAPPER.writeValueAsBytes(claims));
        String signingInput = headerPart + "." + payloadPart;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + base64Url(signature.sign());
    }

    /**
     * 创建 RSA 测试密钥对。
     *
     * @return RSA key pair
     */
    private static KeyPair createRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException("create rsa test key failed", exception);
        }
    }

    /**
     * 将无符号大整数编码为 Base64 URL。
     *
     * @param value 大整数
     * @return Base64 URL 文本
     */
    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] unsigned = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, unsigned, 0, unsigned.length);
            bytes = unsigned;
        }
        return base64Url(bytes);
    }

    /**
     * 将字节数组编码为无 padding 的 Base64 URL。
     *
     * @param bytes 待编码字节
     * @return Base64 URL 文本
     */
    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
