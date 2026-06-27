package site.jianjiange.mobilegateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import site.jianjiange.mobilegateway.config.RateLimitConfig;
import site.jianjiange.mobilegateway.config.RedisConfig;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.exception.BusinessException;
import site.jianjiange.mobilegateway.support.RedisKeyFactory;

/**
 * 基于 Redis 滑动窗口脚本的限流服务单元测试。
 */
class RateLimitServiceTest {

    /**
     * 验证 Redis 滑动窗口脚本返回 1 时请求放行。
     */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void allowsRequestWhenSlidingWindowScriptReturnsOne() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);

        assertThat(service(redisTemplate, true).isAllowed("ip-path", "127.0.0.1:/test"))
                .isTrue();
    }

    /**
     * 验证 Redis 滑动窗口脚本返回 0 时请求被拒绝。
     */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejectsRequestWhenSlidingWindowScriptReturnsZero() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        assertThat(service(redisTemplate, true).isAllowed("ip-path", "127.0.0.1:/test"))
                .isFalse();
    }

    /**
     * 验证 Redis 异常且 fail open 时请求放行。
     */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void allowsRequestWhenRedisFailsAndFailOpenEnabled() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThat(service(redisTemplate, true).isAllowed("ip-path", "127.0.0.1:/test"))
                .isTrue();
    }

    /**
     * 验证 Redis 异常且 fail closed 时返回限流业务异常。
     */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void throwsRateLimitedWhenRedisFailsAndFailOpenDisabled() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThatThrownBy(() -> service(redisTemplate, false).isAllowed("ip-path", "127.0.0.1:/test"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getResultCode()).isEqualTo(ResultCode.RATE_LIMITED));
    }

    /**
     * 创建被测限流服务。
     *
     * @param redisTemplate mock Redis 客户端
     * @param failOpen Redis 异常时是否放行
     * @return 限流服务
     */
    private RateLimitService service(StringRedisTemplate redisTemplate, boolean failOpen) {
        RedisConfig redisConfig = redisConfig();
        return new RateLimitService(redisTemplate, new RedisKeyFactory(redisConfig), rateLimitConfig(failOpen));
    }

    /**
     * 创建测试用 Redis 配置。
     *
     * @return Redis 配置
     */
    private RedisConfig redisConfig() {
        RedisConfig config = new RedisConfig();
        config.setKeyPrefix("dating:mobile-gateway-test");
        return config;
    }

    /**
     * 创建测试用限流配置。
     *
     * @param failOpen Redis 异常时是否放行
     * @return 限流配置
     */
    private RateLimitConfig rateLimitConfig(boolean failOpen) {
        RateLimitConfig config = new RateLimitConfig();
        config.setWindowSeconds(60);
        config.setMaxRequests(3);
        config.setFailOpen(failOpen);
        return config;
    }
}
