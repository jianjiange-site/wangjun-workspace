package site.jianjiange.postservice.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.entity.IdempotentRequestEntity;
import site.jianjiange.postservice.entity.PostEntity;
import site.jianjiange.postservice.entity.PostImageEntity;
import site.jianjiange.postservice.enums.ImageStatus;
import site.jianjiange.postservice.enums.PostStatus;
import site.jianjiange.postservice.exception.BusinessException;
import site.jianjiange.postservice.exception.PostErrorCode;
import site.jianjiange.postservice.mapper.IdempotentRequestMapper;
import site.jianjiange.postservice.mapper.PostImageMapper;
import site.jianjiange.postservice.mapper.PostMapper;

/**
 * 帖子数据管理器，封装 post、post_image 和幂等记录的单表访问。
 */
@Component
public class PostManager {

    private final PostMapper postMapper;
    private final PostImageMapper postImageMapper;
    private final IdempotentRequestMapper idempotentRequestMapper;

    /**
     * 创建帖子数据管理器。
     *
     * @param postMapper 帖子 Mapper
     * @param postImageMapper 图片 Mapper
     * @param idempotentRequestMapper 幂等请求 Mapper
     */
    public PostManager(
            PostMapper postMapper,
            PostImageMapper postImageMapper,
            IdempotentRequestMapper idempotentRequestMapper) {
        this.postMapper = postMapper;
        this.postImageMapper = postImageMapper;
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
     * 绑定当前用户的 TEMP 图片到帖子。
     *
     * @param userId 当前用户 ID
     * @param postId 帖子技术主键
     * @param imageNos 图片业务号列表
     * @param now 当前时间
     */
    public void bindTempImages(Long userId, Long postId, List<Long> imageNos, OffsetDateTime now) {
        if (imageNos == null || imageNos.isEmpty()) {
            return;
        }
        List<PostImageEntity> images = postImageMapper.selectList(new LambdaQueryWrapper<PostImageEntity>()
                .in(PostImageEntity::getImageNo, imageNos));
        if (images.size() != imageNos.size()) {
            throw new BusinessException(PostErrorCode.IMAGE_NOT_FOUND, "图片不存在");
        }

        Map<Long, PostImageEntity> imageMap = images.stream()
                .collect(Collectors.toMap(PostImageEntity::getImageNo, Function.identity()));
        List<PostImageEntity> orderedImages = new ArrayList<>();
        for (Long imageNo : imageNos) {
            PostImageEntity image = imageMap.get(imageNo);
            if (!userId.equals(image.getUserId())) {
                throw new BusinessException(PostErrorCode.IMAGE_FORBIDDEN, "只能绑定自己的图片");
            }
            if (image.getStatus() != ImageStatus.TEMP || image.getPostId() != null) {
                throw new BusinessException(PostErrorCode.IMAGE_STATUS_INVALID, "只能绑定 TEMP 图片");
            }
            orderedImages.add(image);
        }

        for (int index = 0; index < orderedImages.size(); index++) {
            PostImageEntity image = orderedImages.get(index);
            int updated = postImageMapper.update(null, new LambdaUpdateWrapper<PostImageEntity>()
                    .eq(PostImageEntity::getId, image.getId())
                    .eq(PostImageEntity::getUserId, userId)
                    .eq(PostImageEntity::getStatus, ImageStatus.TEMP)
                    .isNull(PostImageEntity::getPostId)
                    .set(PostImageEntity::getPostId, postId)
                    .set(PostImageEntity::getStatus, ImageStatus.BOUND)
                    .set(PostImageEntity::getSortOrder, index)
                    .set(PostImageEntity::getBoundAt, now)
                    .set(PostImageEntity::getUpdatedAt, now));
            if (updated != 1) {
                throw new BusinessException(PostErrorCode.IMAGE_STATUS_INVALID, "图片状态已变更，请重新上传或刷新后重试");
            }
        }
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
     * 查询帖子绑定图片列表。
     *
     * @param postId 帖子技术主键
     * @return 已绑定图片列表
     */
    public List<PostImageEntity> listBoundImages(Long postId) {
        return postImageMapper.selectList(new LambdaQueryWrapper<PostImageEntity>()
                .eq(PostImageEntity::getPostId, postId)
                .eq(PostImageEntity::getStatus, ImageStatus.BOUND)
                .orderByAsc(PostImageEntity::getSortOrder));
    }

    /**
     * 批量查询多个帖子的已绑定图片，并按帖子技术主键分组，避免列表组装时出现 N+1 查询。
     *
     * @param postIds 帖子技术主键列表
     * @return 按帖子技术主键分组的图片列表
     */
    public Map<Long, List<PostImageEntity>> listBoundImagesByPostIds(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        return postImageMapper.selectList(new LambdaQueryWrapper<PostImageEntity>()
                        .in(PostImageEntity::getPostId, postIds)
                        .eq(PostImageEntity::getStatus, ImageStatus.BOUND)
                        .orderByAsc(PostImageEntity::getPostId, PostImageEntity::getSortOrder))
                .stream()
                .collect(Collectors.groupingBy(
                        PostImageEntity::getPostId,
                        LinkedHashMap::new,
                        Collectors.toList()));
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
