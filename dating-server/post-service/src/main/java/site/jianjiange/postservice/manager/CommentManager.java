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
import org.springframework.transaction.annotation.Transactional;
import site.jianjiange.postservice.constant.DatabaseSentinel;
import site.jianjiange.postservice.entity.CommentEntity;
import site.jianjiange.postservice.entity.PostEntity;
import site.jianjiange.postservice.enums.CommentStatus;
import site.jianjiange.postservice.enums.PostStatus;
import site.jianjiange.postservice.mapper.CommentMapper;
import site.jianjiange.postservice.mapper.PostMapper;

/**
 * 评论数据管理器，封装评论和帖子计数的单表访问。
 */
@Component
public class CommentManager {

    private final CommentMapper commentMapper;
    private final PostMapper postMapper;

    /**
     * 创建评论数据管理器。
     *
     * @param commentMapper 评论 Mapper
     * @param postMapper 帖子 Mapper
     */
    public CommentManager(
            CommentMapper commentMapper,
            PostMapper postMapper) {
        this.commentMapper = commentMapper;
        this.postMapper = postMapper;
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
     * 插入评论。
     *
     * @param comment 评论实体
     */
    public void createComment(CommentEntity comment) {
        commentMapper.insert(comment);
    }

    /**
     * 插入评论，并在需要时同步累加帖子评论数；供异步回写任务保持单条事实的库内一致性。
     *
     * @param comment 评论实体
     * @param increaseCount 是否增加 post.comment_count
     */
    @Transactional
    public void createCommentWithCount(CommentEntity comment, boolean increaseCount) {
        createComment(comment);
        if (increaseCount && !increaseCommentCount(comment.getPostNo(), 1L)) {
            throw new IllegalStateException("评论计数创建回写失败，postNo=" + comment.getPostNo());
        }
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
     * 软删除评论并同步扣减帖子评论数；供异步回写任务保持单条事实的库内一致性。
     *
     * @param commentNo 评论业务号
     * @param authorId 评论作者 ID
     * @param postNo 帖子业务号
     * @param now 删除时间
     */
    @Transactional
    public void softDeleteCommentWithCount(Long commentNo, Long authorId, Long postNo, OffsetDateTime now) {
        if (!softDeleteComment(commentNo, authorId, now)) {
            throw new IllegalStateException("评论删除回写失败，commentNo=" + commentNo);
        }
        if (!increaseCommentCount(postNo, -1L)) {
            throw new IllegalStateException("评论计数删除回写失败，postNo=" + postNo);
        }
    }

    /**
     * 按游标查询帖子一级正常评论。
     *
     * @param postNo 帖子业务号
     * @param cursorCreatedAt 上一页最后一条评论的创建时间；首次查询传哨兵时间
     * @param cursorCommentNo 上一页最后一条评论业务号；首次查询传 -1
     * @param limit 查询数量
     * @return 一级评论列表
     */
    public List<CommentEntity> listNormalRootCommentsAfterCursor(
            Long postNo,
            OffsetDateTime cursorCreatedAt,
            Long cursorCommentNo,
            int limit) {
        int safeLimit = Math.max(1, limit);
        LambdaQueryWrapper<CommentEntity> query = new LambdaQueryWrapper<CommentEntity>()
                .eq(CommentEntity::getPostNo, postNo)
                .eq(CommentEntity::getLevel, 1)
                .eq(CommentEntity::getStatus, CommentStatus.NORMAL);
        if (!DatabaseSentinel.isNoneId(cursorCommentNo)) {
            query.and(cursor -> cursor
                    .gt(CommentEntity::getCreatedAt, cursorCreatedAt)
                    .or()
                    .eq(CommentEntity::getCreatedAt, cursorCreatedAt)
                    .gt(CommentEntity::getCommentNo, cursorCommentNo));
        }
        return commentMapper.selectList(query
                .orderByAsc(CommentEntity::getCreatedAt, CommentEntity::getCommentNo)
                .last("LIMIT " + safeLimit));
    }

    /**
     * 按一级评论统计正常二级回复数量。
     *
     * @param rootCommentNos 一级评论业务号集合
     * @return key 为一级评论业务号、value 为回复数量
     */
    public Map<Long, Long> countNormalRepliesByRootNos(Collection<Long> rootCommentNos) {
        if (rootCommentNos == null || rootCommentNos.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = commentMapper.selectMaps(new QueryWrapper<CommentEntity>()
                .select("root_comment_no", "COUNT(*) AS reply_count")
                .in("root_comment_no", rootCommentNos)
                .eq("level", 2)
                .eq("status", CommentStatus.NORMAL.getValue())
                .groupBy("root_comment_no"));
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long rootCommentNo = toLong(readMapValue(row, "root_comment_no"));
            Long replyCount = toLong(readMapValue(row, "reply_count"));
            if (rootCommentNo != null && replyCount != null) {
                counts.put(rootCommentNo, replyCount);
            }
        }
        return counts;
    }

    /**
     * 统计单个一级评论下的正常二级回复数量。
     *
     * @param rootCommentNo 一级评论业务号
     * @return 回复数量
     */
    public long countNormalRepliesByRootNo(Long rootCommentNo) {
        return commentMapper.selectCount(new LambdaQueryWrapper<CommentEntity>()
                .eq(CommentEntity::getRootCommentNo, rootCommentNo)
                .eq(CommentEntity::getLevel, 2)
                .eq(CommentEntity::getStatus, CommentStatus.NORMAL));
    }

    /**
     * 按页号查询单个一级评论下的正常二级回复。
     *
     * @param rootCommentNo 一级评论业务号
     * @param pageNo 页号，从 1 开始
     * @param pageSize 每页数量
     * @return 二级回复列表
     */
    public List<CommentEntity> listNormalRepliesByRootNoPage(Long rootCommentNo, int pageNo, int pageSize) {
        int safePageNo = Math.max(1, pageNo);
        int safePageSize = Math.max(1, pageSize);
        long offset = (long) (safePageNo - 1) * safePageSize;
        return commentMapper.selectList(new LambdaQueryWrapper<CommentEntity>()
                .eq(CommentEntity::getRootCommentNo, rootCommentNo)
                .eq(CommentEntity::getLevel, 2)
                .eq(CommentEntity::getStatus, CommentStatus.NORMAL)
                .orderByAsc(CommentEntity::getCreatedAt, CommentEntity::getCommentNo)
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
