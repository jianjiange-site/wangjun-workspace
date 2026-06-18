package site.jianjiange.postservice.cache;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.config.CacheKeyPrefixer;
import site.jianjiange.postservice.constant.DatabaseSentinel;
import site.jianjiange.postservice.enums.CommentStatus;

/**
 * 评论缓存，负责 Redis pending 评论、幂等去重和回写锁操作。
 */
@Component
public class CommentCache {

    private static final String COMMENT_IDEMPOTENT_KEY_PREFIX = "comment:idempotent:";
    private static final String COMMENT_PENDING_DATA_KEY_PREFIX = "comment:pending:data:";
    private static final String COMMENT_PENDING_CREATE_QUEUE_KEY = "comment:pending:create";
    private static final String COMMENT_PENDING_CREATE_SET_KEY = "comment:pending:create:set";
    private static final String COMMENT_PENDING_DELETE_QUEUE_KEY = "comment:pending:delete";
    private static final String COMMENT_PENDING_DELETE_SET_KEY = "comment:pending:delete:set";
    private static final String COMMENT_FLUSH_LOCK_KEY = "lock:comment-flush";
    private static final Duration COMMENT_PENDING_TTL = Duration.ofDays(7);
    private static final Duration COMMENT_IDEMPOTENT_TTL = Duration.ofDays(1);
    private static final Duration COMMENT_FLUSH_LOCK_TTL = Duration.ofSeconds(30);
    private static final RedisScript<String> ACCEPT_CREATE_SCRIPT = RedisScript.of("""
            local existing = redis.call('GET', KEYS[1])
            if existing then
                return 'EXISTING|' .. existing
            end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            redis.call('HSET', KEYS[2],
                'id', ARGV[4],
                'commentNo', ARGV[5],
                'postNo', ARGV[6],
                'authorId', ARGV[7],
                'rootCommentNo', ARGV[8],
                'parentCommentNo', ARGV[9],
                'replyToUserId', ARGV[10],
                'content', ARGV[11],
                'level', ARGV[12],
                'status', ARGV[13],
                'createdAt', ARGV[14],
                'deletedAt', ARGV[15],
                'persisted', ARGV[16])
            redis.call('EXPIRE', KEYS[2], ARGV[3])
            redis.call('RPUSH', KEYS[3], ARGV[5])
            redis.call('EXPIRE', KEYS[3], ARGV[3])
            redis.call('SADD', KEYS[4], ARGV[5])
            redis.call('EXPIRE', KEYS[4], ARGV[3])
            return 'CREATED|' .. ARGV[1]
            """, String.class);
    private static final RedisScript<Long> ACCEPT_DELETE_SCRIPT = RedisScript.of("""
            if redis.call('SISMEMBER', KEYS[3], ARGV[3]) == 1 then
                return 0
            end
            redis.call('HSET', KEYS[1],
                'id', ARGV[2],
                'commentNo', ARGV[3],
                'postNo', ARGV[4],
                'authorId', ARGV[5],
                'rootCommentNo', ARGV[6],
                'parentCommentNo', ARGV[7],
                'replyToUserId', ARGV[8],
                'content', ARGV[9],
                'level', ARGV[10],
                'status', ARGV[11],
                'createdAt', ARGV[12],
                'deletedAt', ARGV[13],
                'persisted', ARGV[14])
            redis.call('EXPIRE', KEYS[1], ARGV[1])
            redis.call('RPUSH', KEYS[2], ARGV[3])
            redis.call('EXPIRE', KEYS[2], ARGV[1])
            redis.call('SADD', KEYS[3], ARGV[3])
            redis.call('EXPIRE', KEYS[3], ARGV[1])
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
     * 创建评论缓存。
     *
     * @param cacheKeyPrefixer Redis Key 前缀工具
     * @param stringRedisTemplate 字符串 Redis 模板
     */
    public CommentCache(
            CacheKeyPrefixer cacheKeyPrefixer,
            StringRedisTemplate stringRedisTemplate) {
        this.cacheKeyPrefixer = cacheKeyPrefixer;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 接收一条待回写评论，Redis 成功写入后由后台任务异步落库。
     *
     * @param record 待回写评论
     * @param requestHash 请求哈希
     * @param clientRequestId 客户端请求 ID
     * @return 接收结果
     */
    public PendingCommentAcceptResult acceptCreate(
            PendingCommentRecord record,
            String requestHash,
            String clientRequestId) {
        String idempotentValue = requestHash + "|" + record.commentNo();
        String result = stringRedisTemplate.execute(
                ACCEPT_CREATE_SCRIPT,
                List.of(
                        idempotentKey(record.authorId(), clientRequestId),
                        pendingDataKey(record.commentNo()),
                        pendingCreateQueueKey(),
                        pendingCreateSetKey()),
                (Object[]) createArgs(record, idempotentValue));
        return parseAcceptCreateResult(result, requestHash, record.commentNo());
    }

    /**
     * 查询 Redis 中已有的评论创建幂等结果。
     *
     * @param authorId 作者 ID
     * @param clientRequestId 客户端请求 ID
     * @param requestHash 当前请求哈希
     * @return 已存在的幂等结果
     */
    public Optional<PendingCommentAcceptResult> findCreateResult(
            Long authorId,
            String clientRequestId,
            String requestHash) {
        String existingValue = stringRedisTemplate.opsForValue().get(idempotentKey(authorId, clientRequestId));
        if (existingValue == null) {
            return Optional.empty();
        }
        IdempotentPayload payload = parseIdempotentPayload(existingValue);
        return Optional.of(new PendingCommentAcceptResult(
                payload.commentNo(),
                true,
                !requestHash.equals(payload.requestHash())));
    }

    /**
     * 接收一条待回写删除事实。
     *
     * @param record 待删除评论事实，状态应为 USER_DELETED
     * @return 首次接收删除返回 true，重复删除返回 false
     */
    public boolean acceptDelete(PendingCommentRecord record) {
        Long accepted = stringRedisTemplate.execute(
                ACCEPT_DELETE_SCRIPT,
                List.of(
                        pendingDataKey(record.commentNo()),
                        pendingDeleteQueueKey(),
                        pendingDeleteSetKey()),
                (Object[]) deleteArgs(record));
        return Long.valueOf(1L).equals(accepted);
    }

    /**
     * 根据评论业务号查询 pending 评论。
     *
     * @param commentNo 评论业务号
     * @return pending 评论
     */
    public Optional<PendingCommentRecord> findPendingComment(Long commentNo) {
        return readPendingRecord(pendingDataKey(commentNo));
    }

    /**
     * 判断评论是否存在待回写删除事实。
     *
     * @param commentNo 评论业务号
     * @return 存在时返回 true
     */
    public boolean isPendingDeleted(Long commentNo) {
        Boolean member = stringRedisTemplate.opsForSet()
                .isMember(pendingDeleteSetKey(), commentNo.toString());
        return Boolean.TRUE.equals(member);
    }

    /**
     * 判断评论是否仍有待回写创建事实。
     *
     * @param commentNo 评论业务号
     * @return 存在时返回 true
     */
    public boolean hasPendingCreate(Long commentNo) {
        Boolean member = stringRedisTemplate.opsForSet()
                .isMember(pendingCreateSetKey(), commentNo.toString());
        return Boolean.TRUE.equals(member);
    }

    /**
     * 读取待回写创建队列。
     *
     * @return 评论业务号列表
     */
    public List<Long> listPendingCreateCommentNos() {
        return readQueue(pendingCreateQueueKey());
    }

    /**
     * 读取待回写删除队列。
     *
     * @return 评论业务号列表
     */
    public List<Long> listPendingDeleteCommentNos() {
        return readQueue(pendingDeleteQueueKey());
    }

    /**
     * 确认创建回写成功，移除创建队列和 pending 数据。
     *
     * @param record 已回写评论
     */
    public void ackCreate(PendingCommentRecord record) {
        stringRedisTemplate.opsForList()
                .remove(pendingCreateQueueKey(), 0, record.commentNo().toString());
        if (record.status() == CommentStatus.NORMAL) {
            stringRedisTemplate.opsForSet()
                    .remove(pendingCreateSetKey(), record.commentNo().toString());
        } else if (!isPendingDeleted(record.commentNo())) {
            stringRedisTemplate.opsForSet()
                    .remove(pendingCreateSetKey(), record.commentNo().toString());
        }
        if (!isPendingDeleted(record.commentNo())) {
            deletePendingRecord(record);
        }
    }

    /**
     * 确认删除回写成功，移除删除队列和 tombstone。
     *
     * @param record 已回写删除评论
     */
    public void ackDelete(PendingCommentRecord record) {
        stringRedisTemplate.opsForList()
                .remove(pendingDeleteQueueKey(), 0, record.commentNo().toString());
        stringRedisTemplate.opsForSet()
                .remove(pendingDeleteSetKey(), record.commentNo().toString());
        stringRedisTemplate.opsForSet()
                .remove(pendingCreateSetKey(), record.commentNo().toString());
        deletePendingRecord(record);
    }

    /**
     * 清理缺失数据体的创建队列项，避免坏数据反复阻塞回写任务。
     *
     * @param commentNo 评论业务号
     */
    public void discardPendingCreate(Long commentNo) {
        stringRedisTemplate.opsForList().remove(pendingCreateQueueKey(), 0, commentNo.toString());
        stringRedisTemplate.opsForSet().remove(pendingCreateSetKey(), commentNo.toString());
    }

    /**
     * 清理缺失数据体的删除队列项，避免坏数据反复阻塞回写任务。
     *
     * @param commentNo 评论业务号
     */
    public void discardPendingDelete(Long commentNo) {
        stringRedisTemplate.opsForList().remove(pendingDeleteQueueKey(), 0, commentNo.toString());
        stringRedisTemplate.opsForSet().remove(pendingDeleteSetKey(), commentNo.toString());
    }

    /**
     * 尝试获取评论回写锁。
     *
     * @return 获取成功返回锁拥有者 token，失败返回空
     */
    public Optional<String> acquireFlushLock() {
        String ownerToken = UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(commentFlushLockKey(), ownerToken, COMMENT_FLUSH_LOCK_TTL);
        return Boolean.TRUE.equals(acquired) ? Optional.of(ownerToken) : Optional.empty();
    }

    /**
     * 释放评论回写锁；只有锁 value 仍是当前 owner token 时才删除。
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
                List.of(commentFlushLockKey()),
                ownerToken);
        return Long.valueOf(1L).equals(released);
    }

    private PendingCommentAcceptResult parseAcceptCreateResult(
            String result,
            String requestHash,
            Long fallbackCommentNo) {
        if (result == null || result.isBlank()) {
            throw new IllegalStateException("评论 pending 创建 Redis 脚本未返回结果");
        }
        boolean duplicated = result.startsWith("EXISTING|");
        String payloadValue;
        if (duplicated) {
            payloadValue = result.substring("EXISTING|".length());
        } else if (result.startsWith("CREATED|")) {
            payloadValue = result.substring("CREATED|".length());
        } else {
            throw new IllegalStateException("评论 pending 创建 Redis 脚本返回未知结果: " + result);
        }
        IdempotentPayload payload = parseIdempotentPayload(payloadValue);
        Long commentNo = DatabaseSentinel.isNoneId(payload.commentNo()) ? fallbackCommentNo : payload.commentNo();
        return new PendingCommentAcceptResult(
                commentNo,
                duplicated,
                !requestHash.equals(payload.requestHash()));
    }

    private String[] createArgs(PendingCommentRecord record, String idempotentValue) {
        return new String[]{
                idempotentValue,
                String.valueOf(COMMENT_IDEMPOTENT_TTL.toSeconds()),
                String.valueOf(COMMENT_PENDING_TTL.toSeconds()),
                stringValue(record.id()),
                stringValue(record.commentNo()),
                stringValue(record.postNo()),
                stringValue(record.authorId()),
                stringValue(record.rootCommentNo()),
                stringValue(record.parentCommentNo()),
                stringValue(record.replyToUserId()),
                stringValue(record.content()),
                stringValue(record.level()),
                record.status().name(),
                record.createdAt().toString(),
                record.deletedAt().toString(),
                Boolean.toString(record.persisted())
        };
    }

    private String[] deleteArgs(PendingCommentRecord record) {
        return new String[]{
                String.valueOf(COMMENT_PENDING_TTL.toSeconds()),
                stringValue(record.id()),
                stringValue(record.commentNo()),
                stringValue(record.postNo()),
                stringValue(record.authorId()),
                stringValue(record.rootCommentNo()),
                stringValue(record.parentCommentNo()),
                stringValue(record.replyToUserId()),
                stringValue(record.content()),
                stringValue(record.level()),
                record.status().name(),
                record.createdAt().toString(),
                record.deletedAt().toString(),
                Boolean.toString(record.persisted())
        };
    }

    private void deletePendingRecord(PendingCommentRecord record) {
        stringRedisTemplate.delete(pendingDataKey(record.commentNo()));
    }

    private List<Long> readQueue(String key) {
        List<String> values = stringRedisTemplate.opsForList().range(key, 0, -1);
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(this::parseLong)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private Optional<PendingCommentRecord> readPendingRecord(String key) {
        Map<Object, Object> values = stringRedisTemplate.opsForHash().entries(key);
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fromMap(values));
    }

    private PendingCommentRecord fromMap(Map<Object, Object> values) {
        return new PendingCommentRecord(
                longValue(values, "id"),
                longValue(values, "commentNo"),
                longValue(values, "postNo"),
                longValue(values, "authorId"),
                longValue(values, "rootCommentNo"),
                longValue(values, "parentCommentNo"),
                longValue(values, "replyToUserId"),
                stringValue(values, "content"),
                intValue(values, "level"),
                CommentStatus.valueOf(stringValue(values, "status")),
                OffsetDateTime.parse(stringValue(values, "createdAt")),
                OffsetDateTime.parse(stringValue(values, "deletedAt")),
                booleanValue(values, "persisted"));
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Long longValue(Map<Object, Object> values, String key) {
        return parseLong(stringValue(values, key));
    }

    private Integer intValue(Map<Object, Object> values, String key) {
        String value = stringValue(values, key);
        return value == null || value.isBlank() ? null : Integer.valueOf(value);
    }

    private boolean booleanValue(Map<Object, Object> values, String key) {
        return Boolean.parseBoolean(stringValue(values, key));
    }

    private String stringValue(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : value.toString();
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private IdempotentPayload parseIdempotentPayload(String value) {
        if (value == null || value.isBlank()) {
            return new IdempotentPayload("", DatabaseSentinel.NONE_ID);
        }
        int separator = value.lastIndexOf('|');
        if (separator < 0) {
            return new IdempotentPayload(value, DatabaseSentinel.NONE_ID);
        }
        Long commentNo = parseLong(value.substring(separator + 1));
        return new IdempotentPayload(value.substring(0, separator),
                commentNo == null ? DatabaseSentinel.NONE_ID : commentNo);
    }

    private String idempotentKey(Long userId, String clientRequestId) {
        return cacheKeyPrefixer.prefix(COMMENT_IDEMPOTENT_KEY_PREFIX + userId + ":" + clientRequestId);
    }

    private String pendingDataKey(Long commentNo) {
        return cacheKeyPrefixer.prefix(COMMENT_PENDING_DATA_KEY_PREFIX + commentNo);
    }

    private String pendingCreateQueueKey() {
        return cacheKeyPrefixer.prefix(COMMENT_PENDING_CREATE_QUEUE_KEY);
    }

    private String pendingCreateSetKey() {
        return cacheKeyPrefixer.prefix(COMMENT_PENDING_CREATE_SET_KEY);
    }

    private String pendingDeleteQueueKey() {
        return cacheKeyPrefixer.prefix(COMMENT_PENDING_DELETE_QUEUE_KEY);
    }

    private String pendingDeleteSetKey() {
        return cacheKeyPrefixer.prefix(COMMENT_PENDING_DELETE_SET_KEY);
    }

    private String commentFlushLockKey() {
        return cacheKeyPrefixer.prefix(COMMENT_FLUSH_LOCK_KEY);
    }

    private record IdempotentPayload(String requestHash, Long commentNo) {
    }
}
