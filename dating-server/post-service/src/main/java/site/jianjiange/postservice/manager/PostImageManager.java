package site.jianjiange.postservice.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.constant.DatabaseSentinel;
import site.jianjiange.postservice.entity.PostImageEntity;
import site.jianjiange.postservice.enums.ImageStatus;
import site.jianjiange.postservice.exception.BusinessException;
import site.jianjiange.postservice.exception.PostErrorCode;
import site.jianjiange.postservice.mapper.PostImageMapper;
import site.jianjiange.postservice.storage.ObjectMetadata;
import site.jianjiange.postservice.storage.ObjectStorageClient;
import site.jianjiange.postservice.storage.ObjectStorageException;
import site.jianjiange.postservice.storage.ObjectStorageObjectNotFoundException;

/**
 * 帖子图片管理器，负责图片归属、状态、对象存储元数据校验和绑定更新。
 */
@Component
public class PostImageManager {

    private static final int MAX_IMAGE_COUNT = 9;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp");

    private final PostImageMapper postImageMapper;
    private final ObjectStorageClient objectStorageClient;

    /**
     * 创建帖子图片管理器。
     *
     * @param postImageMapper 帖子图片 Mapper
     * @param objectStorageClient 对象存储客户端
     */
    public PostImageManager(PostImageMapper postImageMapper, ObjectStorageClient objectStorageClient) {
        this.postImageMapper = postImageMapper;
        this.objectStorageClient = objectStorageClient;
    }

    /**
     * 校验图片可绑定性，并在事务外确认对象已真实上传。
     *
     * @param userId 当前用户 ID
     * @param imageNos 图片业务号列表
     * @return 按入参顺序排列的图片实体
     */
    public List<PostImageEntity> prepareBindableImages(Long userId, List<Long> imageNos) {
        if (imageNos == null || imageNos.isEmpty()) {
            return List.of();
        }
        if (imageNos.size() > MAX_IMAGE_COUNT) {
            throw new BusinessException(PostErrorCode.IMAGE_LIMIT_EXCEEDED, "帖子最多绑定 9 张图片");
        }
        if (new LinkedHashSet<>(imageNos).size() != imageNos.size()) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, "图片业务号不能重复");
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
            validateOwnerAndState(userId, image);
            validateUploadedObject(image);
            orderedImages.add(image);
        }
        return List.copyOf(orderedImages);
    }

    /**
     * 绑定当前用户已经校验过的 TEMP 图片到帖子。
     *
     * @param userId 当前用户 ID
     * @param postNo 帖子业务号
     * @param images 图片实体列表
     * @param now 当前时间
     */
    public void bindTempImages(Long userId, Long postNo, List<PostImageEntity> images, OffsetDateTime now) {
        if (images == null || images.isEmpty()) {
            return;
        }
        for (int index = 0; index < images.size(); index++) {
            PostImageEntity image = images.get(index);
            int updated = postImageMapper.update(null, new LambdaUpdateWrapper<PostImageEntity>()
                    .eq(PostImageEntity::getId, image.getId())
                    .eq(PostImageEntity::getUserId, userId)
                    .eq(PostImageEntity::getStatus, ImageStatus.TEMP)
                    .eq(PostImageEntity::getPostNo, DatabaseSentinel.NONE_ID)
                    .set(PostImageEntity::getPostNo, postNo)
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
     * 查询帖子绑定图片列表。
     *
     * @param postNo 帖子业务号
     * @return 已绑定图片列表
     */
    public List<PostImageEntity> listBoundImages(Long postNo) {
        return postImageMapper.selectList(new LambdaQueryWrapper<PostImageEntity>()
                .eq(PostImageEntity::getPostNo, postNo)
                .eq(PostImageEntity::getStatus, ImageStatus.BOUND)
                .orderByAsc(PostImageEntity::getSortOrder));
    }

    /**
     * 批量查询多个帖子的已绑定图片，并按帖子业务号分组。
     *
     * @param postNos 帖子业务号列表
     * @return 按帖子业务号分组的图片列表
     */
    public Map<Long, List<PostImageEntity>> listBoundImagesByPostNos(List<Long> postNos) {
        if (postNos == null || postNos.isEmpty()) {
            return Map.of();
        }
        return postImageMapper.selectList(new LambdaQueryWrapper<PostImageEntity>()
                        .in(PostImageEntity::getPostNo, postNos)
                        .eq(PostImageEntity::getStatus, ImageStatus.BOUND)
                        .orderByAsc(PostImageEntity::getPostNo, PostImageEntity::getSortOrder))
                .stream()
                .collect(Collectors.groupingBy(
                        PostImageEntity::getPostNo,
                        LinkedHashMap::new,
                        Collectors.toList()));
    }

    /**
     * 校验图片归属和绑定状态。
     *
     * @param userId 当前用户 ID
     * @param image 图片实体
     */
    private void validateOwnerAndState(Long userId, PostImageEntity image) {
        if (image == null) {
            throw new BusinessException(PostErrorCode.IMAGE_NOT_FOUND, "图片不存在");
        }
        if (!userId.equals(image.getUserId())) {
            throw new BusinessException(PostErrorCode.IMAGE_FORBIDDEN, "只能绑定自己的图片");
        }
        if (image.getStatus() != ImageStatus.TEMP || !DatabaseSentinel.isNoneId(image.getPostNo())) {
            throw new BusinessException(PostErrorCode.IMAGE_STATUS_INVALID, "只能绑定 TEMP 图片");
        }
    }

    /**
     * 校验对象已经上传，并且对象大小和内容类型符合规则。
     *
     * @param image 图片实体
     */
    private void validateUploadedObject(PostImageEntity image) {
        ObjectMetadata metadata;
        try {
            metadata = objectStorageClient.statObject(image.getBucket(), image.getObjectKey());
        } catch (ObjectStorageObjectNotFoundException ex) {
            throw new BusinessException(PostErrorCode.IMAGE_NOT_UPLOADED, "图片尚未上传完成");
        } catch (ObjectStorageException ex) {
            throw new BusinessException(PostErrorCode.MINIO_OPERATION_FAILED, "对象存储校验失败");
        }

        if (metadata.sizeBytes() <= 0
                || image.getSizeBytes() <= 0
                || metadata.sizeBytes() != image.getSizeBytes()) {
            throw new BusinessException(PostErrorCode.IMAGE_NOT_UPLOADED, "图片大小不合法");
        }
        String dbContentType = normalizeContentType(image.getContentType());
        String objectContentType = normalizeContentType(metadata.contentType());
        if (!ALLOWED_CONTENT_TYPES.contains(dbContentType)
                || !ALLOWED_CONTENT_TYPES.contains(objectContentType)
                || !objectContentType.equals(dbContentType)) {
            throw new BusinessException(PostErrorCode.IMAGE_CONTENT_TYPE_INVALID, "图片类型不合法");
        }
    }

    /**
     * 规整内容类型，去除空白并转为小写。
     *
     * @param contentType 内容类型
     * @return 规整后的内容类型
     */
    private String normalizeContentType(String contentType) {
        return contentType == null ? "" : contentType.trim().toLowerCase();
    }
}
