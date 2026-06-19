package site.jianjiange.postservice.converter;

import java.util.List;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.proto.Comment;
import site.jianjiange.postservice.proto.ListCommentRepliesResponse;
import site.jianjiange.postservice.proto.ListPostCommentsResponse;
import site.jianjiange.postservice.service.result.CommentPageResult;
import site.jianjiange.postservice.service.result.CommentResult;

/**
 * 评论业务结果到 proto 的转换器。
 */
@Component
public class CommentProtoConverter {

    /**
     * 转换一级评论分页。
     *
     * @param result 评论分页业务结果
     * @return proto 响应
     */
    public ListPostCommentsResponse toListPostCommentsResponse(CommentPageResult result) {
        ListPostCommentsResponse.Builder builder = ListPostCommentsResponse.newBuilder()
                .setPageSize(result.pageSize())
                .setHasNext(result.hasNext())
                .setNextCursorCreatedAt(GrpcTimeConverter.toTimestamp(result.nextCursorCreatedAt()))
                .setNextCursorCommentNo(result.nextCursorCommentNo());
        comments(result).forEach(comment -> builder.addComments(toComment(comment)));
        return builder.build();
    }

    /**
     * 转换二级回复分页。
     *
     * @param result 评论分页业务结果
     * @return proto 响应
     */
    public ListCommentRepliesResponse toListCommentRepliesResponse(CommentPageResult result) {
        ListCommentRepliesResponse.Builder builder = ListCommentRepliesResponse.newBuilder()
                .setPageNo(result.pageNo())
                .setPageSize(result.pageSize())
                .setTotalCount(result.totalCount())
                .setTotalPages(result.totalPages())
                .setHasPrevious(result.hasPrevious())
                .setHasNext(result.hasNext());
        comments(result).forEach(comment -> builder.addComments(toComment(comment)));
        return builder.build();
    }

    /**
     * 转换评论。
     *
     * @param result 评论业务结果
     * @return proto 评论
     */
    public Comment toComment(CommentResult result) {
        Comment.Builder builder = Comment.newBuilder()
                .setCommentNo(result.commentNo())
                .setPostNo(result.postNo())
                .setAuthorId(result.authorId())
                .setRootCommentNo(result.rootCommentNo())
                .setParentCommentNo(result.parentCommentNo())
                .setReplyToUserId(result.replyToUserId())
                .setContent(result.content())
                .setLevel(result.level())
                .setCreatedAt(GrpcTimeConverter.toTimestamp(result.createdAt()))
                .setReplyTotalCount(result.replyTotalCount())
                .setReplyPageSize(result.replyPageSize())
                .setReplyTotalPages(result.replyTotalPages());
        replies(result).forEach(reply -> builder.addReplies(toComment(reply)));
        return builder.build();
    }

    private List<CommentResult> comments(CommentPageResult result) {
        return result.comments() == null ? List.of() : result.comments();
    }

    private List<CommentResult> replies(CommentResult result) {
        return result.replies() == null ? List.of() : result.replies();
    }
}
