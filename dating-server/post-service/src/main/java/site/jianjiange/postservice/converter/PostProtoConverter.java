package site.jianjiange.postservice.converter;

import java.util.List;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.proto.Post;
import site.jianjiange.postservice.proto.PostImage;
import site.jianjiange.postservice.service.result.FeedPageResult;
import site.jianjiange.postservice.service.result.PostImageResult;
import site.jianjiange.postservice.service.result.PostResult;
import site.jianjiange.postservice.proto.GetFeedResponse;
import site.jianjiange.postservice.proto.ListAuthorPostsResponse;

/**
 * 帖子业务结果到 proto 的转换器。
 */
@Component
public class PostProtoConverter {

    /**
     * 转换帖子详情。
     *
     * @param result 帖子业务结果
     * @return proto 帖子
     */
    public Post toPost(PostResult result) {
        Post.Builder builder = Post.newBuilder()
                .setPostNo(result.postNo())
                .setAuthorId(result.authorId())
                .setContent(result.content())
                .setImageCount(result.imageCount())
                .setStatus(result.status().name())
                .setLikeCount(result.likeCount())
                .setCommentCount(result.commentCount())
                .setPublishedAt(GrpcTimeConverter.toTimestamp(result.publishedAt()));
        images(result).forEach(image -> builder.addImages(toPostImage(image)));
        return builder.build();
    }

    /**
     * 转换作者帖子列表。
     *
     * @param posts 帖子列表
     * @param pageSize 页大小
     * @return proto 响应
     */
    public ListAuthorPostsResponse toListAuthorPostsResponse(List<PostResult> posts, int pageSize) {
        ListAuthorPostsResponse.Builder builder = ListAuthorPostsResponse.newBuilder()
                .setPageSize(pageSize);
        posts.forEach(post -> builder.addPosts(toPost(post)));
        return builder.build();
    }

    /**
     * 转换 Feed 页。
     *
     * @param result Feed 业务结果
     * @return proto 响应
     */
    public GetFeedResponse toFeedResponse(FeedPageResult result) {
        GetFeedResponse.Builder builder = GetFeedResponse.newBuilder()
                .setNextCursor(result.nextCursor())
                .setPageSize(result.pageSize())
                .setHasNext(result.hasNext());
        result.posts().forEach(post -> builder.addPosts(toPost(post)));
        return builder.build();
    }

    /**
     * 转换图片。
     *
     * @param image 图片业务结果
     * @return proto 图片
     */
    public PostImage toPostImage(PostImageResult image) {
        return PostImage.newBuilder()
                .setImageId(image.imageNo())
                .setBucket(image.bucket())
                .setObjectKey(image.objectKey())
                .setContentType(image.contentType())
                .setSizeBytes(image.sizeBytes())
                .setWidth(image.width())
                .setHeight(image.height())
                .setSortOrder(image.sortOrder())
                .build();
    }

    private List<PostImageResult> images(PostResult result) {
        return result.images() == null ? List.of() : result.images();
    }
}
