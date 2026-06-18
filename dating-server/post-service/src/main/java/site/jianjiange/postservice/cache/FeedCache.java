package site.jianjiange.postservice.cache;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.config.CacheKeyPrefixer;
import site.jianjiange.postservice.enums.UserGender;

/**
 * Feed 缓存，负责性别缓存、热门/新帖候选和短期翻页状态。
 */
@Component
public class FeedCache {

    private static final String PROFILE_GENDER_KEY_PREFIX = "profile:gender:";
    private static final String FEED_HOT_KEY_PREFIX = "feed:hot:gender:";
    private static final String FEED_NEW_KEY_PREFIX = "feed:new:gender:";
    private static final String FEED_LIKED_AUTHOR_LATEST_KEY_PREFIX = "feed:liked:author:latest:";
    private static final String FEED_LIKED_CONSUMED_KEY_PREFIX = "feed:liked:consumed:";
    private static final String FEED_NEW_CURSOR_KEY_PREFIX = "feed:new:cursor:";
    private static final String FEED_KEY_VERSION = ":v1";
    private static final Duration PROFILE_GENDER_TTL = Duration.ofHours(1);
    private static final Duration HOT_FEED_TTL = Duration.ofMinutes(30);
    private static final Duration NEW_FEED_TTL = Duration.ofDays(3);
    private static final Duration LIKED_AUTHOR_LATEST_TTL = Duration.ofDays(1);
    private static final Duration LIKED_CONSUMED_TTL = Duration.ofDays(1);
    private static final Duration NEW_CURSOR_TTL = Duration.ofMinutes(30);

    private final CacheKeyPrefixer cacheKeyPrefixer;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 创建 Feed 缓存。
     *
     * @param cacheKeyPrefixer Redis Key 前缀工具
     * @param stringRedisTemplate 字符串 Redis 模板
     */
    public FeedCache(CacheKeyPrefixer cacheKeyPrefixer, StringRedisTemplate stringRedisTemplate) {
        this.cacheKeyPrefixer = cacheKeyPrefixer;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 读取用户性别缓存。
     *
     * @param userId 用户 ID
     * @return 缓存命中的性别
     */
    public Optional<UserGender> findCachedGender(Long userId) {
        String value = stringRedisTemplate.opsForValue().get(profileGenderKey(userId));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UserGender.valueOf(value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * 写入用户性别缓存。
     *
     * @param userId 用户 ID
     * @param gender 用户性别
     */
    public void cacheGender(Long userId, UserGender gender) {
        if (userId == null || gender == null) {
            return;
        }
        stringRedisTemplate.opsForValue()
                .set(profileGenderKey(userId), gender.name(), PROFILE_GENDER_TTL);
    }

    /**
     * 读取目标性别热门候选。
     *
     * @param authorGender 作者性别桶
     * @param offset 读取起始偏移量
     * @param limit 最大返回数量
     * @return 按热度从高到低排列的帖子业务号
     */
    public List<Long> listHotPostNos(UserGender authorGender, int offset, int limit) {
        if (limit <= 0) {
            return List.of();
        }

        int safeOffset = Math.max(0, offset);
        Set<String> values = stringRedisTemplate.opsForZSet()
                .reverseRange(hotFeedKey(authorGender), safeOffset, safeOffset + limit - 1L);
        return parsePostNos(values);
    }

    /**
     * 原子替换目标性别热门候选。
     *
     * @param authorGender 作者性别桶
     * @param scores key 为帖子业务号，value 为热度分
     */
    public void replaceHotPostNos(UserGender authorGender, Map<Long, Double> scores) {
        String key = hotFeedKey(authorGender);
        stringRedisTemplate.delete(key);
        if (scores == null || scores.isEmpty()) {
            return;
        }
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        scores.forEach((postNo, score) -> tuples.add(new DefaultTypedTuple<>(
                postNo.toString(),
                score == null ? 0.0D : score)));
        stringRedisTemplate.opsForZSet().add(key, tuples);
        stringRedisTemplate.expire(key, HOT_FEED_TTL);
    }

    /**
     * 写入新帖性别候选桶。
     *
     * @param authorGender 作者性别
     * @param postNo 帖子业务号
     * @param publishedAt 发布时间
     */
    public void addNewPost(UserGender authorGender, Long postNo, OffsetDateTime publishedAt) {
        if (authorGender == null || postNo == null || publishedAt == null) {
            return;
        }
        String key = newFeedKey(authorGender);
        stringRedisTemplate.opsForZSet().add(key, postNo.toString(), toEpochMillis(publishedAt));
        stringRedisTemplate.opsForZSet()
                .removeRangeByScore(key, 0, toEpochMillis(OffsetDateTime.now().minus(NEW_FEED_TTL)));
        stringRedisTemplate.expire(key, NEW_FEED_TTL);
    }

    /**
     * 保存作者最新帖子，用于喜欢过的人 Feed 召回；同一作者发新帖会覆盖旧帖。
     *
     * @param authorId 作者 ID
     * @param postNo 最新帖子业务号
     */
    public void saveLikedAuthorLatestPost(Long authorId, Long postNo) {
        if (authorId == null || postNo == null) {
            return;
        }
        stringRedisTemplate.opsForValue()
                .set(likedAuthorLatestKey(authorId), postNo.toString(), LIKED_AUTHOR_LATEST_TTL);
    }

    /**
     * 批量读取喜欢过的作者最新帖子；没有 Redis 命中时调用方不再回库扫描作者帖子。
     *
     * @param authorIds 喜欢过的作者 ID 集合
     * @return 按输入作者顺序返回的作者最新帖子映射
     */
    public Map<Long, Long> findLikedAuthorLatestPostNos(Collection<Long> authorIds) {
        List<Long> normalizedAuthorIds = normalizeIds(authorIds);
        if (normalizedAuthorIds.isEmpty()) {
            return Map.of();
        }
        List<String> keys = normalizedAuthorIds.stream()
                .map(this::likedAuthorLatestKey)
                .toList();
        List<String> values = stringRedisTemplate.opsForValue().multiGet(keys);
        Map<Long, Long> latestPostNos = new LinkedHashMap<>();
        for (int index = 0; index < normalizedAuthorIds.size(); index++) {
            String value = values == null || index >= values.size() ? null : values.get(index);
            Optional<Long> postNo = parseLong(value);
            if (postNo.isPresent()) {
                latestPostNos.put(normalizedAuthorIds.get(index), postNo.orElseThrow());
            }
        }
        return latestPostNos;
    }

    /**
     * 删除已确认无效的作者最新帖子记录。
     *
     * @param authorIds 最新帖子不存在或不可见的作者 ID 集合
     */
    public void deleteInvalidLikedAuthorLatestPosts(Collection<Long> authorIds) {
        List<Long> normalizedAuthorIds = normalizeIds(authorIds);
        if (normalizedAuthorIds.isEmpty()) {
            return;
        }
        stringRedisTemplate.delete(normalizedAuthorIds.stream()
                .map(this::likedAuthorLatestKey)
                .toList());
    }

    /**
     * 按时间游标读取新帖候选。
     *
     * @param authorGender 作者性别桶
     * @param beforeTime 只读取早于该时间的帖子
     * @param limit 最大返回数量
     * @return 按发布时间从新到旧排列的帖子业务号
     */
    public List<Long> listNewPostNosBefore(UserGender authorGender, OffsetDateTime beforeTime, int limit) {
        long maxScore = Math.max(0L, toEpochMillis(beforeTime) - 1L);
        Set<String> values = stringRedisTemplate.opsForZSet()
                .reverseRangeByScore(newFeedKey(authorGender), 0, maxScore, 0, Math.max(1, limit));
        return parsePostNos(values);
    }

    /**
     * 读取用户新帖时间游标。
     *
     * @param userId 用户 ID
     * @param targetGender 目标作者性别
     * @return 新帖时间游标
     */
    public Optional<OffsetDateTime> findNewCursor(Long userId, UserGender targetGender) {
        String value = stringRedisTemplate.opsForValue().get(newCursorKey(userId, targetGender));
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(fromEpochMillis(Long.parseLong(value)));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /**
     * 保存用户新帖时间游标。
     *
     * @param userId 用户 ID
     * @param targetGender 目标作者性别
     * @param beforeTime 下一页读取时间游标
     */
    public void saveNewCursor(Long userId, UserGender targetGender, OffsetDateTime beforeTime) {
        stringRedisTemplate.opsForValue()
                .set(newCursorKey(userId, targetGender), String.valueOf(toEpochMillis(beforeTime)), NEW_CURSOR_TTL);
    }

    /**
     * 按输入顺序过滤当前用户已经消费过的喜欢作者最新帖。
     *
     * @param userId 用户 ID
     * @param postNosByAuthor key 为作者 ID，value 为最新帖子业务号
     * @return 当前用户未消费过的作者最新帖子映射
     */
    public Map<Long, Long> filterUnconsumedLikedLatestPosts(Long userId, Map<Long, Long> postNosByAuthor) {
        if (userId == null || postNosByAuthor == null || postNosByAuthor.isEmpty()) {
            return Map.of();
        }
        String key = likedConsumedKey(userId);
        Map<Long, Long> unconsumed = new LinkedHashMap<>();
        postNosByAuthor.forEach((authorId, postNo) -> {
            if (authorId == null || postNo == null) {
                return;
            }
            if (!Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(
                    key, likedConsumedValue(authorId, postNo)))) {
                unconsumed.put(authorId, postNo);
            }
        });
        return unconsumed;
    }

    /**
     * 标记当前用户已经消费过的喜欢作者最新帖。
     *
     * @param userId 用户 ID
     * @param postNosByAuthor key 为作者 ID，value 为已进入 Feed 的帖子业务号
     */
    public void markLikedConsumed(Long userId, Map<Long, Long> postNosByAuthor) {
        if (userId == null || postNosByAuthor == null || postNosByAuthor.isEmpty()) {
            return;
        }
        List<String> values = postNosByAuthor.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .map(entry -> likedConsumedValue(entry.getKey(), entry.getValue()))
                .toList();
        if (values.isEmpty()) {
            return;
        }
        String key = likedConsumedKey(userId);
        stringRedisTemplate.opsForSet().add(key, values.toArray(String[]::new));
        stringRedisTemplate.expire(key, LIKED_CONSUMED_TTL);
    }

    /**
     * 主动刷新时清空用户短期翻页状态。
     *
     * @param userId 用户 ID
     * @param targetGender 目标作者性别
     */
    public void clearUserState(Long userId, UserGender targetGender) {
        stringRedisTemplate.delete(newCursorKey(userId, targetGender));
    }

    private List<Long> parsePostNos(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(this::parseLong)
                .filter(Optional::isPresent)
                .map(Optional::orElseThrow)
                .toList();
    }

    private Optional<Long> parseLong(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private List<Long> normalizeIds(Collection<Long> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private long toEpochMillis(OffsetDateTime time) {
        return time.toInstant().toEpochMilli();
    }

    private OffsetDateTime fromEpochMillis(long epochMillis) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }

    private String profileGenderKey(Long userId) {
        return cacheKeyPrefixer.prefix(PROFILE_GENDER_KEY_PREFIX + userId);
    }

    private String hotFeedKey(UserGender gender) {
        return cacheKeyPrefixer.prefix(FEED_HOT_KEY_PREFIX + gender.name() + FEED_KEY_VERSION);
    }

    private String newFeedKey(UserGender gender) {
        return cacheKeyPrefixer.prefix(FEED_NEW_KEY_PREFIX + gender.name() + FEED_KEY_VERSION);
    }

    private String likedAuthorLatestKey(Long authorId) {
        return cacheKeyPrefixer.prefix(FEED_LIKED_AUTHOR_LATEST_KEY_PREFIX + authorId);
    }

    private String likedConsumedKey(Long userId) {
        return cacheKeyPrefixer.prefix(FEED_LIKED_CONSUMED_KEY_PREFIX + userId);
    }

    private String likedConsumedValue(Long authorId, Long postNo) {
        return authorId + ":" + postNo;
    }

    private String newCursorKey(Long userId, UserGender targetGender) {
        return cacheKeyPrefixer.prefix(FEED_NEW_CURSOR_KEY_PREFIX + userId + ":" + targetGender.name());
    }
}
