package site.jianjiange.mobilegateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * refresh token 并发轮换测试。
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class RefreshTokenConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 20;

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
     * 验证 20 个并发请求刷新同一个 refresh token 时只有一个成功，其余返回重复使用。
     */
    @Test
    void concurrentRefreshOnlyAllowsOneSuccessfulRotation() throws Exception {
        JsonNode login = deviceLogin("device-key-stage4-concurrency");
        String oldRefreshToken = login.at("/data/refresh_token").asText();
        String oldJti = parseRefreshJti(oldRefreshToken);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                futures.add(executor.submit(refreshTask(oldRefreshToken, start)));
            }
            start.countDown();

            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                codes.add(future.get());
            }

            assertThat(codes).containsOnly(0, 10506);
            assertThat(codes.stream().filter(code -> code == 0).count()).isEqualTo(1);
            assertThat(codes.stream().filter(code -> code == 10506).count()).isEqualTo(CONCURRENT_REQUESTS - 1);

            GatewayRefreshTokenEntity oldToken = refreshTokenMapper.selectOne(
                    new LambdaQueryWrapper<GatewayRefreshTokenEntity>()
                            .eq(GatewayRefreshTokenEntity::getTokenJti, oldJti)
                            .last("limit 1"));
            assertThat(oldToken.getStatus()).isEqualTo(AuthConstants.REFRESH_STATUS_ROTATED);
            Long activeTokenCount = refreshTokenMapper.selectCount(
                    new LambdaQueryWrapper<GatewayRefreshTokenEntity>()
                            .eq(GatewayRefreshTokenEntity::getUserId, login.at("/data/user_id").asLong())
                            .eq(GatewayRefreshTokenEntity::getStatus, AuthConstants.REFRESH_STATUS_ACTIVE)
                            .eq(GatewayRefreshTokenEntity::getDeleted, AuthConstants.DELETED_NO));
            assertThat(activeTokenCount).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 构造等待统一起跑的 refresh 请求任务。
     *
     * @param refreshToken refresh token 明文
     * @param start 起跑闩
     * @return 返回业务码的任务
     */
    private Callable<Integer> refreshTask(String refreshToken, CountDownLatch start) {
        return () -> {
            start.await();
            MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("refresh_token", refreshToken))))
                    .andExpect(status().isOk())
                    .andReturn();
            return objectMapper.readTree(result.getResponse().getContentAsByteArray()).at("/code").asInt();
        };
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
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
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
