package site.jianjiange.mobilegateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import site.jianjiange.mobilegateway.client.SmsProviderClient;
import site.jianjiange.mobilegateway.client.UserGrpcClient;
import site.jianjiange.mobilegateway.entity.GatewayAccountEntity;
import site.jianjiange.mobilegateway.entity.GatewayRefreshTokenEntity;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.exception.BusinessException;
import site.jianjiange.mobilegateway.manager.AuthConstants;
import site.jianjiange.mobilegateway.mapper.GatewayAccountMapper;
import site.jianjiange.mobilegateway.mapper.GatewayRefreshTokenMapper;
import site.jianjiange.mobilegateway.support.HmacHasher;
import site.jianjiange.mobilegateway.support.RedisKeyFactory;

/**
 * 手机验证码登录闭环集成测试。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class PhoneLoginIntegrationTest {

    private static final String PHONE = "+8613800000000";

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

    @Autowired
    private RedisKeyFactory redisKeyFactory;

    @MockBean
    private UserGrpcClient userGrpcClient;

    @MockBean
    private SmsProviderClient smsProviderClient;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    private final Map<String, String> redisValues = new ConcurrentHashMap<>();

    /**
     * 清理认证数据并初始化外部依赖 mock。
     */
    @BeforeEach
    void setUp() {
        redisValues.clear();
        jdbcTemplate.update("DELETE FROM gateway_refresh_token");
        jdbcTemplate.update("DELETE FROM gateway_device");
        jdbcTemplate.update("DELETE FROM gateway_account");
        when(userGrpcClient.registerOrInitialize(anyLong(), anyString(), anyString()))
                .thenReturn(new UserGrpcClient.RegisterOrInitializeResult(0, "ok"));
        mockRedis();
    }

    /**
     * 验证发送验证码会写入 hash 和冷却 key，重复发送返回 10201。
     */
    @Test
    void sendSmsCodeWritesHashAndCooldownThenRejectsDuplicateSend() throws Exception {
        sendSmsCodeExpectCode(PHONE, 0);
        String code = captureLastSmsCode();
        String phoneHash = hmacHasher.hashPhone(PHONE);

        assertThat(redisValues.get(redisKeyFactory.smsCode(phoneHash))).hasSize(64);
        assertThat(redisValues.get(redisKeyFactory.smsCode(phoneHash))).doesNotContain(code);
        assertThat(redisValues.get(redisKeyFactory.smsCooldown(phoneHash))).isEqualTo("1");

        sendSmsCodeExpectCode(PHONE, ResultCode.SMS_CODE_SEND_TOO_FREQUENT.getCode());
        verify(smsProviderClient, times(1)).sendCode(eq(PHONE), anyString());
    }

    /**
     * 验证错误验证码返回 10200，失败次数达到阈值后返回 10202。
     */
    @Test
    void wrongSmsCodeIncrementsFailureCountAndThenRejectsTooFrequentVerify() throws Exception {
        sendSmsCodeExpectCode(PHONE, 0);
        String actualCode = captureLastSmsCode();
        String wrongCode = actualCode.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            phoneLoginExpectCode(PHONE, wrongCode, ResultCode.SMS_CODE_INVALID.getCode());
        }
        phoneLoginExpectCode(PHONE, wrongCode, ResultCode.SMS_CODE_VERIFY_TOO_FREQUENT.getCode());

        String phoneHash = hmacHasher.hashPhone(PHONE);
        assertThat(redisValues.get(redisKeyFactory.loginFail(AuthConstants.ACCOUNT_TYPE_PHONE, phoneHash)))
                .isEqualTo("5");
    }

    /**
     * 验证正确验证码会创建 PHONE 账号、消费验证码、签发 token，再次登录复用同一 user_id。
     */
    @Test
    void correctSmsCodeCreatesPhoneAccountIssuesTokensAndReusesUser() throws Exception {
        sendSmsCodeExpectCode(PHONE, 0);
        String firstCode = captureLastSmsCode();
        JsonNode firstLogin = phoneLogin(PHONE, firstCode);
        String phoneHash = hmacHasher.hashPhone(PHONE);

        assertThat(firstLogin.at("/code").asInt()).isZero();
        assertThat(firstLogin.at("/data/token_type").asText()).isEqualTo("Bearer");
        assertThat(redisValues).doesNotContainKey(redisKeyFactory.smsCode(phoneHash));

        GatewayAccountEntity account = accountMapper.selectOne(new LambdaQueryWrapper<GatewayAccountEntity>()
                .eq(GatewayAccountEntity::getAccountType, AuthConstants.ACCOUNT_TYPE_PHONE)
                .eq(GatewayAccountEntity::getAccountKeyHash, phoneHash)
                .last("limit 1"));
        assertThat(account).isNotNull();
        assertThat(account.getUserRegisterStatus()).isEqualTo(AuthConstants.REGISTER_STATUS_DONE);
        assertThat(account.getAccountKeyHash()).doesNotContain(PHONE);
        assertThat(refreshTokenMapper.selectCount(new LambdaQueryWrapper<GatewayRefreshTokenEntity>()
                .eq(GatewayRefreshTokenEntity::getUserId, account.getUserId()))).isEqualTo(1);

        redisValues.remove(redisKeyFactory.smsCooldown(phoneHash));
        sendSmsCodeExpectCode(PHONE, 0);
        String secondCode = captureLastSmsCode();
        JsonNode secondLogin = phoneLogin(PHONE, secondCode);

        assertThat(secondLogin.at("/data/user_id").asLong()).isEqualTo(firstLogin.at("/data/user_id").asLong());
        assertThat(accountMapper.selectCount(new LambdaQueryWrapper<GatewayAccountEntity>()
                .eq(GatewayAccountEntity::getAccountType, AuthConstants.ACCOUNT_TYPE_PHONE)
                .eq(GatewayAccountEntity::getAccountKeyHash, phoneHash))).isEqualTo(1);
        verify(userGrpcClient, times(1)).registerOrInitialize(anyLong(),
                eq(AuthConstants.ACCOUNT_TYPE_PHONE), eq(AuthConstants.REGISTER_SOURCE_PHONE_LOGIN));
    }

    /**
     * 验证 user-service 初始化失败时不签发 token，账号保持待初始化。
     */
    @Test
    void userServiceInitializeFailureKeepsPhoneAccountPendingAndDoesNotIssueToken() throws Exception {
        when(userGrpcClient.registerOrInitialize(anyLong(), anyString(), anyString()))
                .thenThrow(new BusinessException(ResultCode.USER_REGISTER_INITIALIZE_FAILED));

        sendSmsCodeExpectCode(PHONE, 0);
        phoneLoginExpectCode(PHONE, captureLastSmsCode(), ResultCode.USER_REGISTER_INITIALIZE_FAILED.getCode());

        GatewayAccountEntity account = accountMapper.selectOne(new LambdaQueryWrapper<GatewayAccountEntity>()
                .eq(GatewayAccountEntity::getAccountType, AuthConstants.ACCOUNT_TYPE_PHONE)
                .last("limit 1"));
        assertThat(account).isNotNull();
        assertThat(account.getUserRegisterStatus()).isEqualTo(AuthConstants.REGISTER_STATUS_PENDING);
        assertThat(refreshTokenMapper.selectCount(null)).isZero();
    }

    /**
     * 初始化一个足够覆盖阶段 5 行为的内存 Redis mock。
     */
    private void mockRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            return redisValues.putIfAbsent(key, value) == null;
        });
        doAnswer(invocation -> {
            redisValues.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        when(valueOperations.get(anyString())).thenAnswer(invocation -> redisValues.get(invocation.getArgument(0)));
        when(valueOperations.increment(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            long next = Long.parseLong(redisValues.getOrDefault(key, "0")) + 1;
            redisValues.put(key, String.valueOf(next));
            return next;
        });
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.delete(anyString())).thenAnswer(invocation -> redisValues.remove(invocation.getArgument(0)) != null);
    }

    /**
     * 调用发送验证码接口并断言业务码。
     *
     * @param phone 手机号
     * @param code 期望业务码
     * @throws Exception 请求失败时抛出
     */
    private void sendSmsCodeExpectCode(String phone, int code) throws Exception {
        mockMvc.perform(post("/api/v1/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", phone))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code));
    }

    /**
     * 调用手机登录接口。
     *
     * @param phone 手机号
     * @param code 验证码
     * @return 登录响应 JSON
     * @throws Exception 请求失败时抛出
     */
    private JsonNode phoneLogin(String phone, String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login/phone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", phone, "code", code))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    /**
     * 调用手机登录接口并断言业务码。
     *
     * @param phone 手机号
     * @param smsCode 验证码
     * @param code 期望业务码
     * @throws Exception 请求失败时抛出
     */
    private void phoneLoginExpectCode(String phone, String smsCode, int code) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login/phone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", phone, "code", smsCode))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code));
    }

    /**
     * 捕获最后一次 mock 短信发送的验证码。
     *
     * @return 最后一次发送验证码
     */
    private String captureLastSmsCode() {
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(smsProviderClient, org.mockito.Mockito.atLeastOnce()).sendCode(eq(PHONE), codeCaptor.capture());
        return codeCaptor.getAllValues().get(codeCaptor.getAllValues().size() - 1);
    }
}
