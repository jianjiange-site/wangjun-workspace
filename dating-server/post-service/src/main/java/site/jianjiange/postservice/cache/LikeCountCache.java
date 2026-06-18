package site.jianjiange.postservice.cache;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.config.CacheKeyPrefixer;

/**
 * 点赞缓存，负责 Redis TTL 去重、实时计数 delta 和回写锁操作。
 */
@Component
public class LikeCountCache {

    private static final String LIKE_USER_KEY_PREFIX = "like:users:";
    private static final String LIKE_DELTA_KEY_PREFIX = "like:delta:";
    private static final String LIKE_FLUSHING_KEY_PREFIX = "like:flushing:";
    private static final String LIKE_FLUSH_LOCK_KEY = "lock:like-flush";
    private static final Duration LIKE_DEDUPE_TTL = Duration.ofDays(7);
    private static final Duration LIKE_BUFFER_TTL = Duration.ofDays(7);
    private static final Duration LIKE_FLUSH_LOCK_TTL = Duration.ofSeconds(30);
    private static final long SCAN_COUNT = 1000L;
    private static final RedisScript<Long> ACCEPT_LIKE_SCRIPT = RedisScript.of("""
            if redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) then
                redis.call('INCR', KEYS[2])
                redis.call('EXPIRE', KEYS[2], ARGV[2])
                return 1
            end
            return 0
            """, Long.class);
    private static final RedisScript<Long> MOVE_TO_FLUSHING_SCRIPT = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 0
            end
            if redis.call('EXISTS', KEYS[2]) == 1 then
                return 0
            end
            redis.call('RENAME', KEYS[1], KEYS[2])
            redis.call('EXPIRE', KEYS[2], ARGV[1])
            return 1
            """, Long.class);
    private static final RedisScript<Long> RELEASE_FLUSH_LOCK_SCRIPT = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final CacheKeyPrefixer cacheKeyPrefixer;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 创建点赞计数缓存。
     *
     * @param cacheKeyPrefixer Redis Key 前缀工具
     * @param stringRedisTemplate 字符串 Redis 模板
     */
    public LikeCountCache(
            CacheKeyPrefixer cacheKeyPrefixer,
            StringRedisTemplate stringRedisTemplate) {
        this.cacheKeyPrefixer = cacheKeyPrefixer;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 原子接受一次点赞：TTL 去重成功时写入实时 delta。
     *
     * @param record 待计数点赞记录
     * @return TTL 窗口内首次点赞返回 true，重复点赞返回 false
     */
    public boolean acceptLike(PendingLikeRecord record) {
        Long accepted = stringRedisTemplate.execute(
                ACCEPT_LIKE_SCRIPT,
                List.of(
                        likeUserDedupeKey(record.postNo(), record.userId()),
                        likeDeltaKey(record.postNo())),
                String.valueOf(LIKE_DEDUPE_TTL.toSeconds()),
                String.valueOf(LIKE_BUFFER_TTL.toSeconds()));
        return Long.valueOf(1L).equals(accepted);
    }

    /**
     * 读取单个帖子的待回写点赞 delta。
     *
     * @param postNo 帖子业务号
     * @return 待回写点赞增量，key 不存在或无法解析时返回 0
     */
    public long readLikeDelta(Long postNo) {
        return readCount(likeDeltaKey(postNo)) + readCount(likeFlushingKey(postNo));
    }

    /**
     * 批量读取帖子待回写点赞 delta。
     *
     * @param postNos 帖子业务号集合
     * @return 以帖子业务号为 key 的待回写点赞增量
     */
    public Map<Long, Long> readLikeDeltas(Collection<Long> postNos) {
        if (postNos == null || postNos.isEmpty()) {
            return Map.of();
        }
        List<Long> normalizedPostNos = postNos.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (normalizedPostNos.isEmpty()) {
            return Map.of();
        }
        List<String> deltaKeys = normalizedPostNos.stream()
                .map(this::likeDeltaKey)
                .toList();
        List<String> flushingKeys = normalizedPostNos.stream()
                .map(this::likeFlushingKey)
                .toList();
        List<String> deltaValues = stringRedisTemplate.opsForValue().multiGet(deltaKeys);
        List<String> flushingValues = stringRedisTemplate.opsForValue().multiGet(flushingKeys);
        Map<Long, Long> deltas = new LinkedHashMap<>();
        for (int index = 0; index < normalizedPostNos.size(); index++) {
            String deltaValue = deltaValues == null || index >= deltaValues.size() ? null : deltaValues.get(index);
            String flushingValue = flushingValues == null || index >= flushingValues.size()
                    ? null
                    : flushingValues.get(index);
            deltas.put(normalizedPostNos.get(index), parseCount(deltaValue) + parseCount(flushingValue));
        }
        return deltas;
    }

    /**
     * 尝试获取点赞回写锁。
     *
     * @return 获取成功返回锁拥有者 token，失败返回空
     */
    public Optional<String> acquireFlushLock() {
        String ownerToken = UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(likeFlushLockKey(), ownerToken, LIKE_FLUSH_LOCK_TTL);
        return Boolean.TRUE.equals(acquired) ? Optional.of(ownerToken) : Optional.empty();
    }

    /**
     * 释放点赞回写锁；只有锁 value 仍是当前 owner token 时才删除。
     *
     * @param ownerToken 获取锁时返回的拥有者 token
     * @return 释放成功返回 true，锁已过期或已被其他任务持有时返回 false
     */
    public boolean releaseFlushLock(String ownerToken) {
        if (ownerToken == null || ownerToken.isBlank()) {
            return false;
        }
        Long released = stringRedisTemplate.execute(
                RELEASE_FLUSH_LOCK_SCRIPT,
                List.of(likeFlushLockKey()),
                ownerToken);
        return Long.valueOf(1L).equals(released);
    }

    /**
     * 扫描待转移的点赞 delta key。
     *
     * @return 待回写 delta key 集合
     */
    public Set<String> scanDeltaKeys() {
        return scanKeys(cacheKeyPrefixer.prefix(LIKE_DELTA_KEY_PREFIX + "*"));
    }

    /**
     * 扫描正在回写的点赞 delta key。
     *
     * @return 正在回写 delta key 集合
     */
    public Set<String> scanFlushingKeys() {
        return scanKeys(cacheKeyPrefixer.prefix(LIKE_FLUSHING_KEY_PREFIX + "*"));
    }

    /**
     * 将待回写 delta 原子转移到 flushing key。
     *
     * @param postNo 帖子业务号
     * @return 转移成功返回 true
     */
    public boolean moveLikeBuffersToFlushing(Long postNo) {
        Long moved = stringRedisTemplate.execute(
                MOVE_TO_FLUSHING_SCRIPT,
                List.of(
                        likeDeltaKey(postNo),
                        likeFlushingKey(postNo)),
                String.valueOf(LIKE_BUFFER_TTL.toSeconds()));
        return Long.valueOf(1L).equals(moved);
    }

    /**
     * 读取正在回写的点赞增量。
     *
     * @param postNo 帖子业务号
     * @return 正在回写的点赞增量
     */
    public long readFlushingLikeDelta(Long postNo) {
        return readCount(likeFlushingKey(postNo));
    }

    /**
     * 删除指定帖子的 flushing delta。
     *
     * @param postNo 帖子业务号
     */
    public void deleteFlushingBuffers(Long postNo) {
        stringRedisTemplate.delete(likeFlushingKey(postNo));
    }

    /**
     * 读取指定 key 的计数值。
     *
     * @param key Redis key
     * @return 计数值，key 不存在或无法解析时返回 0
     */
    public long readCount(String key) {
        String value = stringRedisTemplate.opsForValue().get(key);
        return parseCount(value);
    }

    /**
     * 解析 Redis 字符串计数。
     *
     * @param value Redis 字符串值
     * @return 可用计数，空值或非法值返回 0
     */
    private long parseCount(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    /**
     * 删除指定 Redis key。
     *
     * @param key Redis key
     */
    public void deleteKey(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 从待回写 delta key 解析帖子业务号。
     *
     * @param key Redis key
     * @return 帖子业务号
     */
    public Optional<Long> parsePostNoFromDeltaKey(String key) {
        return parsePostNo(key, cacheKeyPrefixer.prefix(LIKE_DELTA_KEY_PREFIX));
    }

    /**
     * 从正在回写 delta key 解析帖子业务号。
     *
     * @param key Redis key
     * @return 帖子业务号
     */
    public Optional<Long> parsePostNoFromFlushingKey(String key) {
        return parsePostNo(key, cacheKeyPrefixer.prefix(LIKE_FLUSHING_KEY_PREFIX));
    }

    /**
     * 构造 flushing key。
     *
     * @param postNo 帖子业务号
     * @return flushing key
     */
    public String likeFlushingKey(Long postNo) {
        return cacheKeyPrefixer.prefix(LIKE_FLUSHING_KEY_PREFIX + postNo);
    }

    /**
     * 构造用户-帖子点赞 TTL 去重 key。
     *
     * @param postNo 帖子业务号
     * @param userId 点赞用户 ID
     * @return TTL 去重 key
     */
    private String likeUserDedupeKey(Long postNo, Long userId) {
        return cacheKeyPrefixer.prefix(LIKE_USER_KEY_PREFIX + postNo + ":" + userId);
    }

    /**
     * 构造 delta key。
     *
     * @param postNo 帖子业务号
     * @return delta key
     */
    private String likeDeltaKey(Long postNo) {
        return cacheKeyPrefixer.prefix(LIKE_DELTA_KEY_PREFIX + postNo);
    }

    /**
     * 构造点赞回写锁 key。
     *
     * @return 点赞回写锁 key
     */
    private String likeFlushLockKey() {
        return cacheKeyPrefixer.prefix(LIKE_FLUSH_LOCK_KEY);
    }

    /**
     * 使用 Redis SCAN 查找 key，避免在共享 Redis 上执行 KEYS。
     *
     * @param pattern Redis key pattern
     * @return 匹配到的 key 集合
     */
    private Set<String> scanKeys(String pattern) {
        Set<String> keys = stringRedisTemplate.execute((RedisConnection connection) -> {
            Set<String> result = new LinkedHashSet<>();
            ScanOptions options = ScanOptions.scanOptions()
                    .match(pattern)
                    .count(SCAN_COUNT)
                    .build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                cursor.forEachRemaining(keyBytes ->
                        result.add(new String(keyBytes, StandardCharsets.UTF_8)));
            }
            return result;
        });
        return keys == null ? Set.of() : keys;
    }

    /**
     * 根据 key 前缀解析帖子业务号。
     *
     * @param key Redis key
     * @param prefix key 前缀
     * @return 解析成功时返回帖子业务号
     */
    private Optional<Long> parsePostNo(String key, String prefix) {
        if (key == null || !key.startsWith(prefix)) {
            return Optional.empty();
        }
        String postNo = key.substring(prefix.length());
        try {
            return Optional.of(Long.parseLong(postNo));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }
}
