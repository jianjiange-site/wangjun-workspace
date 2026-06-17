package site.jianjiange.postservice.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.entity.CommentEntity;
import site.jianjiange.postservice.entity.IdempotentRequestEntity;
import site.jianjiange.postservice.entity.PostEntity;
import site.jianjiange.postservice.enums.CommentStatus;
import site.jianjiange.postservice.enums.PostStatus;
import site.jianjiange.postservice.mapper.CommentMapper;
import site.jianjiange.postservice.mapper.IdempotentRequestMapper;
import site.jianjiange.postservice.mapper.PostMapper;

/**
 * 评论数据管理器，封装评论、帖子和幂等记录的单表访问。
 */
@Component
public class CommentManager {

    private final CommentMapper commentMapper;
    private final PostMapper postMapper;
    private final IdempotentRequestMapper idempotentRequestMapper;

    /**
     * 创建评论数据管理器。
     *
     * @param commentMapper 评论 Mapper
     * @param postMapper 帖子 Mapper
     * @param idempotentRequestMapper 幂等请求 Mapper
     */
    public CommentManager(
            CommentMapper commentMapper,
            PostMapper postMapper,
            IdempotentRequestMapper idempotentRequestMapper) {
        this.commentMapper = commentMapper;
        this.postMapper = postMapper;
        this.idempotentRequestMapper = idempotentRequestMapper;
    }

    /**
     * 根据帖子业务号查询帖子。
     *
     * @param postNo 帖子业务号
     * @return 帖子实体，未命中时返回 null
     */
    public PostEntity findPostByPostNo(Long postNo) {
        return postMapper.selectOne(new LambdaQueryWrapper<PostEntity>()
                .eq(PostEntity::getPostNo, postNo));
    }

    /**
     * 根据评论业务号查询评论。
     *
     * @param commentNo 评论业务号
     * @return 评论实体，未命中时返回 null
     */
    public CommentEntity findByCommentNo(Long commentNo) {
        return commentMapper.selectOne(new LambdaQueryWrapper<CommentEntity>()
                .eq(CommentEntity::getCommentNo, commentNo));
    }

    /**
     * 根据评论技术主键批量查询评论。
     *
     * @param ids 评论技术主键集合
     * @return 评论列表
     */
    public List<CommentEntity> listByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return commentMapper.selectBatchIds(ids);
    }

    /**
     * 根据幂等键查询已有请求记录。
     *
     * @param userId 用户 ID
     * @param operationType 操作类型
     * @param clientRequestId 客户端请求 ID
     * @return 幂等请求记录，未命中时返回 null
     */
    public IdempotentRequestEntity findIdempotentRequest(
            Long userId, String operationType, String clientRequestId) {
        return idempotentRequestMapper.selectOne(new LambdaQueryWrapper<IdempotentRequestEntity>()
                .eq(IdempotentRequestEntity::getUserId, userId)
                .eq(IdempotentRequestEntity::getOperationType, operationType)
                .eq(IdempotentRequestEntity::getClientRequestId, clientRequestId));
    }

    /**
     * 插入评论。
     *
     * @param comment 评论实体
     */
    public void createComment(CommentEntity comment) {
        commentMapper.insert(comment);
    }

    /**
     * 插入幂等请求记录。
     *
     * @param request 幂等请求实体
     */
    public void createIdempotentRequest(IdempotentRequestEntity request) {
        idempotentRequestMapper.insert(request);
    }

    /**
     * 累加帖子评论数。
     *
     * @param postNo 帖子业务号
     * @param delta 增量
     * @return 更新成功返回 true
     */
    public boolean increaseCommentCount(Long postNo, long delta) {
        if (delta == 0) {
            return true;
        }
        String expression = delta > 0
                ? "comment_count = comment_count + " + delta
                : "comment_count = comment_count - " + Math.abs(delta);
        return postMapper.update(null, new LambdaUpdateWrapper<PostEntity>()
                .eq(PostEntity::getPostNo, postNo)
                .eq(PostEntity::getStatus, PostStatus.PUBLISHED)
                .setSql(expression)
                .set(PostEntity::getUpdatedAt, OffsetDateTime.now())) == 1;
    }

    /**
     * 将评论软删除为用户删除状态。
     *
     * @param commentNo 评论业务号
     * @param authorId 作者 ID
     * @param now 当前时间
     * @return 是否更新成功
     */
    public boolean softDeleteComment(Long commentNo, Long authorId, OffsetDateTime now) {
        return commentMapper.update(null, new LambdaUpdateWrapper<CommentEntity>()
                .eq(CommentEntity::getCommentNo, commentNo)
                .eq(CommentEntity::getAuthorId, authorId)
                .eq(CommentEntity::getStatus, CommentStatus.NORMAL)
                .set(CommentEntity::getStatus, CommentStatus.USER_DELETED)
                .set(CommentEntity::getDeletedAt, now)) == 1;
    }

    /**
     * 统计帖子一级正常评论数量。
     *
     * @param postNo 帖子业务号
     * @return 一级评论数量
     */
    public long countNormalRootComments(Long postNo) {
        return commentMapper.selectCount(new LambdaQueryWrapper<CommentEntity>()
                .eq(CommentEntity::getPostNo, postNo)
                .eq(CommentEntity::getLevel, 1)
                .eq(CommentEntity::getStatus, CommentStatus.NORMAL));
    }

    /**
     * 按页号查询帖子一级正常评论。
     *
     * @param postNo 帖子业务号
     * @param pageNo 页号，从 1 开始
     * @param pageSize 每页数量
     * @return 一级评论列表
     */
    public List<CommentEntity> listNormalRootCommentsPage(Long postNo, int pageNo, int pageSize) {
        int safePageNo = Math.max(1, pageNo);
        int safePageSize = Math.max(1, pageSize);
        long offset = (long) (safePageNo - 1) * safePageSize;
        return commentMapper.selectList(new LambdaQueryWrapper<CommentEntity>()
                .eq(CommentEntity::getPostNo, postNo)
                .eq(CommentEntity::getLevel, 1)
                .eq(CommentEntity::getStatus, CommentStatus.NORMAL)
                .orderByAsc(CommentEntity::getCreatedAt, CommentEntity::getId)
                .last("LIMIT " + safePageSize + " OFFSET " + offset));
    }

    /**
     * 按一级评论统计正常二级回复数量。
     *
     * @param rootCommentIds 一级评论技术主键集合
     * @return key 为一级评论技术主键、value 为回复数量
     */
    public Map<Long, Long> countNormalRepliesByRootIds(Collection<Long> rootCommentIds) {
        if (rootCommentIds == null || rootCommentIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = commentMapper.selectMaps(new QueryWrapper<CommentEntity>()
                .select("root_comment_id", "COUNT(*) AS reply_count")
                .in("root_comment_id", rootCommentIds)
                .eq("level", 2)
                .eq("status", CommentStatus.NORMAL.getValue())
                .groupBy("root_comment_id"));
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long rootCommentId = toLong(readMapValue(row, "root_comment_id"));
            Long replyCount = toLong(readMapValue(row, "reply_count"));
            if (rootCommentId != null && replyCount != null) {
                counts.put(rootCommentId, replyCount);
            }
        }
        return counts;
    }

    /**
     * 统计单个一级评论下的正常二级回复数量。
     *
     * @param rootCommentId 一级评论技术主键
     * @return 回复数量
     */
    public long countNormalRepliesByRootId(Long rootCommentId) {
        return commentMapper.selectCount(new LambdaQueryWrapper<CommentEntity>()
                .eq(CommentEntity::getRootCommentId, rootCommentId)
                .eq(CommentEntity::getLevel, 2)
                .eq(CommentEntity::getStatus, CommentStatus.NORMAL));
    }

    /**
     * 按页号查询单个一级评论下的正常二级回复。
     *
     * @param rootCommentId 一级评论技术主键
     * @param pageNo 页号，从 1 开始
     * @param pageSize 每页数量
     * @return 二级回复列表
     */
    public List<CommentEntity> listNormalRepliesByRootIdPage(Long rootCommentId, int pageNo, int pageSize) {
        int safePageNo = Math.max(1, pageNo);
        int safePageSize = Math.max(1, pageSize);
        long offset = (long) (safePageNo - 1) * safePageSize;
        return commentMapper.selectList(new LambdaQueryWrapper<CommentEntity>()
                .eq(CommentEntity::getRootCommentId, rootCommentId)
                .eq(CommentEntity::getLevel, 2)
                .eq(CommentEntity::getStatus, CommentStatus.NORMAL)
                .orderByAsc(CommentEntity::getCreatedAt, CommentEntity::getId)
                .last("LIMIT " + safePageSize + " OFFSET " + offset));
    }

    private Object readMapValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value != null) {
            return value;
        }
        return row.get(key.toUpperCase());
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }
}
