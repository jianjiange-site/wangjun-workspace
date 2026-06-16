package site.jianjiange.postservice.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.entity.IdempotentRequestEntity;
import site.jianjiange.postservice.entity.PostEntity;
import site.jianjiange.postservice.enums.PostStatus;
import site.jianjiange.postservice.mapper.IdempotentRequestMapper;
import site.jianjiange.postservice.mapper.PostMapper;

/**
 * 帖子数据管理器，封装 post 和幂等记录的单表访问。
 */
@Component
public class PostManager {

    private final PostMapper postMapper;
    private final IdempotentRequestMapper idempotentRequestMapper;

    /**
     * 创建帖子数据管理器。
     *
     * @param postMapper 帖子 Mapper
     * @param idempotentRequestMapper 幂等请求 Mapper
     */
    public PostManager(PostMapper postMapper, IdempotentRequestMapper idempotentRequestMapper) {
        this.postMapper = postMapper;
        this.idempotentRequestMapper = idempotentRequestMapper;
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
     * 插入帖子记录。
     *
     * @param post 帖子实体
     */
    public void createPost(PostEntity post) {
        postMapper.insert(post);
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
     * 根据帖子业务号查询帖子。
     *
     * @param postNo 帖子业务号
     * @return 帖子实体，未命中时返回 null
     */
    public PostEntity findByPostNo(Long postNo) {
        return postMapper.selectOne(new LambdaQueryWrapper<PostEntity>()
                .eq(PostEntity::getPostNo, postNo));
    }

    /**
     * 根据帖子业务号查询公开可见帖子。
     *
     * @param postNo 帖子业务号
     * @return 公开可见帖子，未命中时返回 null
     */
    public PostEntity findPublishedByPostNo(Long postNo) {
        return postMapper.selectOne(new LambdaQueryWrapper<PostEntity>()
                .eq(PostEntity::getPostNo, postNo)
                .eq(PostEntity::getStatus, PostStatus.PUBLISHED));
    }

    /**
     * 查询作者公开可见帖子列表。
     *
     * @param authorId 作者 ID
     * @param limit 最大返回数量
     * @return 作者帖子列表
     */
    public List<PostEntity> listPublishedByAuthor(Long authorId, int limit) {
        return postMapper.selectList(new LambdaQueryWrapper<PostEntity>()
                .eq(PostEntity::getAuthorId, authorId)
                .eq(PostEntity::getStatus, PostStatus.PUBLISHED)
                .orderByDesc(PostEntity::getCreatedAt)
                .last("LIMIT " + limit));
    }

    /**
     * 将帖子软删除为用户删除状态。
     *
     * @param postId 帖子技术主键
     * @param now 当前时间
     * @return 是否更新成功
     */
    public boolean softDeletePost(Long postId, OffsetDateTime now) {
        return postMapper.update(null, new LambdaUpdateWrapper<PostEntity>()
                .eq(PostEntity::getId, postId)
                .eq(PostEntity::getStatus, PostStatus.PUBLISHED)
                .set(PostEntity::getStatus, PostStatus.USER_DELETED)
                .set(PostEntity::getDeletedAt, now)
                .set(PostEntity::getUpdatedAt, now)) == 1;
    }
}
