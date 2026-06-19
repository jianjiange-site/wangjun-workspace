package site.jianjiange.postservice.cache;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.config.CacheKeyPrefixer;

/**
 * 图片清理缓存，负责 TEMP 图片清理任务的分布式锁。
 */
@Component
public class ImageCleanupCache {

    private static final String IMAGE_CLEAN_LOCK_KEY = "lock:image-clean";
    private static final Duration IMAGE_CLEAN_LOCK_TTL = Duration.ofMinutes(5);
    private static final RedisScript<Long> RELEASE_CLEAN_LOCK_SCRIPT = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final CacheKeyPrefixer cacheKeyPrefixer;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 创建图片清理缓存。
     *
     * @param cacheKeyPrefixer Redis Key 前缀工具
     * @param stringRedisTemplate 字符串 Redis 模板
     */
    public ImageCleanupCache(
            CacheKeyPrefixer cacheKeyPrefixer,
            StringRedisTemplate stringRedisTemplate) {
        this.cacheKeyPrefixer = cacheKeyPrefixer;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 尝试获取图片清理锁。
     *
     * @return 获取成功返回锁拥有者 token，失败返回空
     */
    public Optional<String> acquireCleanLock() {
        String ownerToken = UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(imageCleanLockKey(), ownerToken, IMAGE_CLEAN_LOCK_TTL);
        return Boolean.TRUE.equals(acquired) ? Optional.of(ownerToken) : Optional.empty();
    }

    /**
     * 释放图片清理锁；只有锁 value 仍是当前 owner token 时才删除。
     *
     * @param ownerToken 获取锁时返回的拥有者 token
     * @return 释放成功返回 true，锁已过期或已被其他任务持有时返回 false
     */
    public boolean releaseCleanLock(String ownerToken) {
        if (ownerToken == null || ownerToken.isBlank()) {
            return false;
        }
        Long released = stringRedisTemplate.execute(
                RELEASE_CLEAN_LOCK_SCRIPT,
                List.of(imageCleanLockKey()),
                ownerToken);
        return Long.valueOf(1L).equals(released);
    }

    private String imageCleanLockKey() {
        return cacheKeyPrefixer.prefix(IMAGE_CLEAN_LOCK_KEY);
    }
}
