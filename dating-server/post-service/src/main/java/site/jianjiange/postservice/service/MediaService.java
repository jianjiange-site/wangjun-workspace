package site.jianjiange.postservice.service;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.jianjiange.postservice.config.ObjectStorageProperties;
import site.jianjiange.postservice.constant.DatabaseSentinel;
import site.jianjiange.postservice.entity.PostImageEntity;
import site.jianjiange.postservice.enums.ImageStatus;
import site.jianjiange.postservice.exception.BusinessException;
import site.jianjiange.postservice.exception.PostErrorCode;
import site.jianjiange.postservice.manager.PostImageManager;
import site.jianjiange.postservice.service.command.CreateImageUploadUrlCommand;
import site.jianjiange.postservice.service.result.CreateImageUploadUrlResult;
import site.jianjiange.postservice.storage.ObjectStorageClient;
import site.jianjiange.postservice.storage.ObjectStorageException;

/**
 * 媒体业务服务，负责创建临时图片记录和对象存储上传 URL。
 */
@Service
public class MediaService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp");

    private final IdentifierGenerator identifierGenerator;
    private final PostImageManager postImageManager;
    private final ObjectStorageClient objectStorageClient;
    private final ObjectStorageProperties objectStorageProperties;

    /**
     * 创建媒体业务服务。
     */
    public MediaService(
            IdentifierGenerator identifierGenerator,
            PostImageManager postImageManager,
            ObjectStorageClient objectStorageClient,
            ObjectStorageProperties objectStorageProperties) {
        this.identifierGenerator = identifierGenerator;
        this.postImageManager = postImageManager;
        this.objectStorageClient = objectStorageClient;
        this.objectStorageProperties = objectStorageProperties;
    }

    /**
     * 创建图片上传 URL 和 TEMP 图片记录。
     *
     * @param command 创建上传 URL 命令
     * @return 上传 URL 结果
     */
    @Transactional
    public CreateImageUploadUrlResult createImageUploadUrl(CreateImageUploadUrlCommand command) {
        ValidCreateImageUploadUrlCommand validCommand = validateCommand(command);
        OffsetDateTime now = OffsetDateTime.now();
        PostImageEntity image = new PostImageEntity();
        image.setId(nextBusinessNo(image));
        image.setImageNo(nextBusinessNo(image));
        image.setUserId(validCommand.userId());
        image.setPostNo(DatabaseSentinel.NONE_ID);
        image.setBucket(objectStorageProperties.bucket());
        image.setObjectKey(objectKey(validCommand.userId(), image.getImageNo(), validCommand.contentType()));
        image.setContentType(validCommand.contentType());
        image.setSizeBytes(validCommand.sizeBytes());
        image.setEtag(DatabaseSentinel.NONE_TEXT);
        image.setWidth(validCommand.width());
        image.setHeight(validCommand.height());
        image.setSortOrder(0);
        image.setStatus(ImageStatus.TEMP);
        image.setUploadExpireAt(now.plus(objectStorageProperties.uploadUrlTtl()));
        image.setBoundAt(DatabaseSentinel.NONE_TIME);
        image.setRetryCount(0);
        image.setCreatedAt(now);
        image.setUpdatedAt(now);

        String uploadUrl = createUploadUrl(image);
        postImageManager.createTempImage(image);
        return new CreateImageUploadUrlResult(
                image.getImageNo(),
                image.getObjectKey(),
                uploadUrl,
                image.getUploadExpireAt());
    }

    private String createUploadUrl(PostImageEntity image) {
        try {
            return objectStorageClient.createUploadUrl(
                    image.getBucket(),
                    image.getObjectKey(),
                    image.getContentType(),
                    objectStorageProperties.uploadUrlTtl());
        } catch (ObjectStorageException ex) {
            throw new BusinessException(PostErrorCode.MINIO_OPERATION_FAILED, "创建图片上传 URL 失败");
        }
    }

    private ValidCreateImageUploadUrlCommand validateCommand(CreateImageUploadUrlCommand command) {
        if (command == null) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, "创建图片上传 URL 命令不能为空");
        }
        validatePositive(command.userId(), "userId");
        String contentType = normalizeContentType(command.contentType());
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException(PostErrorCode.IMAGE_CONTENT_TYPE_INVALID, "图片类型不合法");
        }
        validatePositive(command.sizeBytes(), "sizeBytes");
        return new ValidCreateImageUploadUrlCommand(
                command.userId(),
                contentType,
                command.sizeBytes(),
                normalizeDimension(command.width()),
                normalizeDimension(command.height()));
    }

    private String objectKey(Long userId, Long imageNo, String contentType) {
        return "wangjun-tmp/post/"
                + userId
                + "/"
                + imageNo
                + "-"
                + UUID.randomUUID().toString().replace("-", "")
                + extension(contentType);
    }

    private String extension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> "";
        };
    }

    private String normalizeContentType(String contentType) {
        return contentType == null ? "" : contentType.trim().toLowerCase();
    }

    private Integer normalizeDimension(Integer value) {
        return value == null || value <= 0 ? DatabaseSentinel.NONE_NUMBER : value;
    }

    private void validatePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, name + " 必须为正整数");
        }
    }

    private Long nextBusinessNo(Object entity) {
        return identifierGenerator.nextId(entity).longValue();
    }

    private record ValidCreateImageUploadUrlCommand(
            Long userId,
            String contentType,
            Long sizeBytes,
            Integer width,
            Integer height
    ) {
    }
}
