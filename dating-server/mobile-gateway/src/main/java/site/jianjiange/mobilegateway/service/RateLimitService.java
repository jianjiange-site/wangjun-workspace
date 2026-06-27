package site.jianjiange.mobilegateway.service;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import site.jianjiange.mobilegateway.config.RateLimitConfig;
import site.jianjiange.mobilegateway.exception.BusinessException;
import site.jianjiange.mobilegateway.enums.ResultCode;
import site.jianjiange.mobilegateway.support.RedisKeyFactory;

/**
 * 基于 Redis ZSET 滑动窗口的限流服务。
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT = slidingWindowScript();

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory redisKeyFactory;
    private final RateLimitConfig config;

    /**
     * 创建基于 Redis ZSET 滑动窗口的限流服务。
     *
     * @param redisTemplate Redis 字符串客户端
     * @param redisKeyFactory Redis key 工厂
     * @param config 限流配置
     */
    public RateLimitService(StringRedisTemplate redisTemplate, RedisKeyFactory redisKeyFactory,
                            RateLimitConfig config) {
        this.redisTemplate = redisTemplate;
        this.redisKeyFactory = redisKeyFactory;
        this.config = config;
    }

    /**
     * 判断指定维度和 key 在当前窗口内是否允许继续访问。
     *
     * @param dimension 限流维度
     * @param key 限流对象
     * @return 未超过阈值返回 true
     */
    public boolean isAllowed(String dimension, String key) {
        long nowMillis = System.currentTimeMillis();
        long windowMillis = config.getWindowSeconds() * 1000;
        long minScore = nowMillis - windowMillis;
        long ttlSeconds = config.getWindowSeconds() + 10;
        String redisKey = redisKeyFactory.rateLimit(dimension, key);
        String member = nowMillis + ":" + UUID.randomUUID();
        try {
            Long allowed = redisTemplate.execute(
                    SLIDING_WINDOW_SCRIPT,
                    List.of(redisKey),
                    String.valueOf(minScore),
                    String.valueOf(nowMillis),
                    member,
                    String.valueOf(config.getMaxRequests()),
                    String.valueOf(ttlSeconds));
            return allowed == null || allowed == 1L;
        } catch (Exception exception) {
            log.warn("Rate limit Redis operation failed, failOpen={}, key={}", config.isFailOpen(), redisKey, exception);
            if (config.isFailOpen()) {
                return true;
            }
            throw new BusinessException(ResultCode.RATE_LIMITED);
        }
    }

    /**
     * 创建 Redis Lua 脚本，原子执行滑动窗口清理、计数、写入和 TTL 设置。
     *
     * @return Redis Lua 脚本
     */
    private static DefaultRedisScript<Long> slidingWindowScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1])
                local count = redis.call('ZCARD', KEYS[1])
                if count >= tonumber(ARGV[4]) then
                    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5]))
                    return 0
                end
                redis.call('ZADD', KEYS[1], tonumber(ARGV[2]), ARGV[3])
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5]))
                return 1
                """);
        return script;
    }
}
