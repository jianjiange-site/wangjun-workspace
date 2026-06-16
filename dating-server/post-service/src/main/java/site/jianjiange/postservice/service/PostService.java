package site.jianjiange.postservice.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.jianjiange.postservice.entity.IdempotentRequestEntity;
import site.jianjiange.postservice.entity.PostEntity;
import site.jianjiange.postservice.entity.PostImageEntity;
import site.jianjiange.postservice.enums.PostStatus;
import site.jianjiange.postservice.exception.BusinessException;
import site.jianjiange.postservice.exception.PostErrorCode;
import site.jianjiange.postservice.manager.PostManager;
import site.jianjiange.postservice.service.command.CreatePostCommand;
import site.jianjiange.postservice.service.result.CreatePostResult;
import site.jianjiange.postservice.service.result.PostImageResult;
import site.jianjiange.postservice.service.result.PostResult;

/**
 * 帖子业务服务，负责编排创建、软删除、详情和作者帖子列表。
 */
@Service
public class PostService {

    private static final String CREATE_POST_OPERATION = "CREATE_POST";
    private static final int MAX_IMAGE_COUNT = 9;
    private static final int MAX_CONTENT_LENGTH = 2000;
    private static final int MAX_AUTHOR_POST_LIMIT = 50;

    private final IdentifierGenerator identifierGenerator;
    private final PostManager postManager;

    /**
     * 创建帖子业务服务。
     *
     * @param identifierGenerator 业务号生成器
     * @param postManager 帖子数据管理器
     */
    public PostService(IdentifierGenerator identifierGenerator, PostManager postManager) {
        this.identifierGenerator = identifierGenerator;
        this.postManager = postManager;
    }

    /**
     * 创建帖子并绑定 TEMP 图片，同时写入幂等请求记录。
     *
     * @param command 创建帖子命令
     * @return 创建帖子结果
     */
    @Transactional
    public CreatePostResult createPost(CreatePostCommand command) {
        ValidCreatePostCommand validCommand = validateCreatePostCommand(command);
        String requestHash = hashCreatePostRequest(validCommand.content(), validCommand.imageNos());
        IdempotentRequestEntity existing = postManager.findIdempotentRequest(
                validCommand.authorId(), CREATE_POST_OPERATION, validCommand.clientRequestId());
        if (existing != null) {
            if (!requestHash.equals(existing.getRequestHash())) {
                throw new BusinessException(PostErrorCode.IDEMPOTENT_CONFLICT, "幂等请求内容不一致");
            }
            return new CreatePostResult(existing.getBizNo(), true);
        }

        OffsetDateTime now = OffsetDateTime.now();
        PostEntity post = new PostEntity();
        post.setPostNo(nextBusinessNo(post));
        post.setAuthorId(validCommand.authorId());
        post.setContent(validCommand.content());
        post.setImageCount(validCommand.imageNos().size());
        post.setStatus(PostStatus.PUBLISHED);
        post.setLikeCount(0L);
        post.setCommentCount(0L);
        post.setPublishedAt(now);
        post.setVersion(0);
        post.setCreatedAt(now);
        post.setUpdatedAt(now);
        postManager.createPost(post);
        postManager.bindTempImages(validCommand.authorId(), post.getId(), validCommand.imageNos(), now);

        IdempotentRequestEntity request = new IdempotentRequestEntity();
        request.setUserId(validCommand.authorId());
        request.setOperationType(CREATE_POST_OPERATION);
        request.setClientRequestId(validCommand.clientRequestId());
        request.setRequestHash(requestHash);
        request.setBizNo(post.getPostNo());
        request.setResponseSnapshot("{\"postNo\":" + post.getPostNo() + "}");
        request.setCreatedAt(now);
        request.setExpireAt(now.plusDays(1));
        postManager.createIdempotentRequest(request);
        return new CreatePostResult(post.getPostNo(), false);
    }

    /**
     * 软删除帖子，仅允许作者删除公开可见帖子。
     *
     * @param operatorId 操作用户 ID
     * @param postNo 帖子业务号
     */
    @Transactional
    public void deletePost(Long operatorId, Long postNo) {
        validatePositive(operatorId, "operatorId");
        validatePositive(postNo, "postNo");
        PostEntity post = postManager.findByPostNo(postNo);
        if (post == null || post.getStatus() != PostStatus.PUBLISHED) {
            throw new BusinessException(PostErrorCode.POST_NOT_FOUND, "帖子不存在");
        }
        if (!operatorId.equals(post.getAuthorId())) {
            throw new BusinessException(PostErrorCode.POST_FORBIDDEN, "只能删除自己的帖子");
        }
        if (!postManager.softDeletePost(post.getId(), OffsetDateTime.now())) {
            throw new BusinessException(PostErrorCode.POST_NOT_FOUND, "帖子不存在");
        }
    }

    /**
     * 查询帖子详情，只返回公开可见帖子。
     *
     * @param postNo 帖子业务号
     * @return 帖子详情
     */
    @Transactional(readOnly = true)
    public Optional<PostResult> getPostDetail(Long postNo) {
        validatePositive(postNo, "postNo");
        PostEntity post = postManager.findPublishedByPostNo(postNo);
        if (post == null) {
            return Optional.empty();
        }
        return Optional.of(toResult(post, postManager.listBoundImages(post.getId())));
    }

    /**
     * 查询作者公开可见帖子列表。
     *
     * @param authorId 作者 ID
     * @param limit 最大返回数量
     * @return 作者帖子列表
     */
    @Transactional(readOnly = true)
    public List<PostResult> listAuthorPosts(Long authorId, int limit) {
        validatePositive(authorId, "authorId");
        int safeLimit = Math.max(1, Math.min(limit, MAX_AUTHOR_POST_LIMIT));
        List<PostEntity> posts = postManager.listPublishedByAuthor(authorId, safeLimit);
        if (posts.isEmpty()) {
            return List.of();
        }
        Map<Long, List<PostImageEntity>> imagesByPostId = postManager.listBoundImagesByPostIds(
                posts.stream().map(PostEntity::getId).toList());
        return posts
                .stream()
                .map(post -> toResult(post, imagesByPostId.getOrDefault(post.getId(), List.of())))
                .toList();
    }


    /**
     * 转换帖子实体为业务结果。
     *
     * @param post 帖子实体
     * @param images 帖子图片列表
     * @return 帖子业务结果
     */
    private PostResult toResult(PostEntity post, List<PostImageEntity> images) {
        List<PostImageResult> imageResults = images.stream()
                .map(this::toImageResult)
                .toList();
        return new PostResult(
                post.getPostNo(),
                post.getAuthorId(),
                post.getContent(),
                post.getImageCount(),
                post.getStatus(),
                post.getLikeCount(),
                post.getCommentCount(),
                post.getPublishedAt(),
                imageResults);
    }

    /**
     * 转换图片实体为业务结果。
     *
     * @param image 图片实体
     * @return 图片业务结果
     */
    private PostImageResult toImageResult(PostImageEntity image) {
        return new PostImageResult(
                image.getImageNo(),
                image.getBucket(),
                image.getObjectKey(),
                image.getContentType(),
                image.getSizeBytes(),
                image.getWidth(),
                image.getHeight(),
                image.getSortOrder());
    }

    /**
     * 校验创建帖子命令并规整正文和图片列表。
     *
     * @param command 创建帖子命令
     * @return 已规整的创建帖子命令
     */
    private ValidCreatePostCommand validateCreatePostCommand(CreatePostCommand command) {
        if (command == null) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, "创建帖子命令不能为空");
        }
        validatePositive(command.authorId(), "authorId");
        String content = command.content() == null ? "" : command.content().trim();
        if (content.isEmpty()) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, "帖子内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, "帖子内容过长");
        }
        if (isBlank(command.clientRequestId())) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, "clientRequestId 不能为空");
        }
        List<Long> imageNos = command.imageNos() == null ? List.of() : command.imageNos();
        if (imageNos.size() > MAX_IMAGE_COUNT) {
            throw new BusinessException(PostErrorCode.IMAGE_LIMIT_EXCEEDED, "帖子最多绑定 9 张图片");
        }
        List<Long> distinctImageNos = new ArrayList<>(new LinkedHashSet<>(imageNos));
        if (distinctImageNos.size() != imageNos.size()) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, "图片业务号不能重复");
        }
        distinctImageNos.forEach(imageNo -> validatePositive(imageNo, "imageNo"));
        return new ValidCreatePostCommand(command.authorId(), content, distinctImageNos, command.clientRequestId());
    }

    /**
     * 校验正整数参数。
     *
     * @param value 参数值
     * @param name 参数名
     */
    private void validatePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, name + " 必须为正整数");
        }
    }

    /**
     * 判断字符串是否为空白。
     *
     * @param value 待判断字符串
     * @return 为空白时返回 true
     */
    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 计算创建帖子请求哈希。
     *
     * @param content 帖子正文
     * @param imageNos 图片业务号列表
     * @return SHA-256 十六进制哈希
     */
    private String hashCreatePostRequest(String content, List<Long> imageNos) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = content + "|" + imageNos;
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /**
     * 生成业务号，避免对外暴露数据库技术主键。
     *
     * @param entity 参与生成的实体
     * @return 业务号
     */
    private Long nextBusinessNo(Object entity) {
        return identifierGenerator.nextId(entity).longValue();
    }

    /**
     * 已校验并规整后的创建帖子命令。
     */
    private record ValidCreatePostCommand(
            Long authorId,
            String content,
            List<Long> imageNos,
            String clientRequestId) {
    }
}
