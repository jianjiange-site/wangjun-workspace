package site.jianjiange.postservice.grpc;

import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;
import java.time.OffsetDateTime;
import net.devh.boot.grpc.server.service.GrpcService;
import site.jianjiange.postservice.constant.DatabaseSentinel;
import site.jianjiange.postservice.converter.CommentProtoConverter;
import site.jianjiange.postservice.converter.GrpcTimeConverter;
import site.jianjiange.postservice.proto.CreateCommentRequest;
import site.jianjiange.postservice.proto.CreateCommentResponse;
import site.jianjiange.postservice.proto.DeleteCommentRequest;
import site.jianjiange.postservice.proto.InteractionServiceGrpc;
import site.jianjiange.postservice.proto.LikePostRequest;
import site.jianjiange.postservice.proto.LikePostResponse;
import site.jianjiange.postservice.proto.ListCommentRepliesRequest;
import site.jianjiange.postservice.proto.ListCommentRepliesResponse;
import site.jianjiange.postservice.proto.ListPostCommentsRequest;
import site.jianjiange.postservice.proto.ListPostCommentsResponse;
import site.jianjiange.postservice.service.CommentService;
import site.jianjiange.postservice.service.LikeService;
import site.jianjiange.postservice.service.command.CreateCommentCommand;
import site.jianjiange.postservice.service.command.DeleteCommentCommand;
import site.jianjiange.postservice.service.command.LikePostCommand;
import site.jianjiange.postservice.service.result.CommentPageResult;
import site.jianjiange.postservice.service.result.CreateCommentResult;
import site.jianjiange.postservice.service.result.LikePostResult;

/**
 * 点赞和评论 gRPC 入口层。
 */
@GrpcService
public class InteractionGrpcService extends InteractionServiceGrpc.InteractionServiceImplBase {

    private final LikeService likeService;
    private final CommentService commentService;
    private final CommentProtoConverter commentProtoConverter;
    private final GrpcExceptionMapper exceptionMapper;

    /**
     * 创建互动 gRPC 入口。
     */
    public InteractionGrpcService(
            LikeService likeService,
            CommentService commentService,
            CommentProtoConverter commentProtoConverter,
            GrpcExceptionMapper exceptionMapper) {
        this.likeService = likeService;
        this.commentService = commentService;
        this.commentProtoConverter = commentProtoConverter;
        this.exceptionMapper = exceptionMapper;
    }

    @Override
    public void likePost(LikePostRequest request, StreamObserver<LikePostResponse> responseObserver) {
        handle(responseObserver, () -> {
            LikePostResult result = likeService.likePost(new LikePostCommand(
                    GrpcRequestContexts.currentUserId(),
                    request.getPostNo()));
            return LikePostResponse.newBuilder()
                    .setPostNo(result.postNo())
                    .setDuplicated(result.duplicated())
                    .build();
        });
    }

    @Override
    public void createComment(
            CreateCommentRequest request,
            StreamObserver<CreateCommentResponse> responseObserver) {
        handle(responseObserver, () -> {
            CreateCommentResult result = commentService.createComment(new CreateCommentCommand(
                    GrpcRequestContexts.currentUserId(),
                    request.getPostNo(),
                    normalizeParentCommentNo(request.getParentCommentNo()),
                    request.getContent(),
                    clientRequestId(request.getClientRequestId())));
            return CreateCommentResponse.newBuilder()
                    .setCommentNo(result.commentNo())
                    .setDuplicated(result.duplicated())
                    .build();
        });
    }

    @Override
    public void deleteComment(DeleteCommentRequest request, StreamObserver<Empty> responseObserver) {
        handle(responseObserver, () -> {
            commentService.deleteComment(new DeleteCommentCommand(
                    GrpcRequestContexts.currentUserId(),
                    request.getCommentNo()));
            return Empty.getDefaultInstance();
        });
    }

    @Override
    public void listPostComments(
            ListPostCommentsRequest request,
            StreamObserver<ListPostCommentsResponse> responseObserver) {
        handle(responseObserver, () -> {
            OffsetDateTime cursorCreatedAt = request.hasCursorCreatedAt()
                    ? GrpcTimeConverter.toOffsetDateTime(request.getCursorCreatedAt())
                    : null;
            CommentPageResult result = commentService.listPostComments(
                    request.getPostNo(),
                    cursorCreatedAt,
                    normalizeCursorCommentNo(request.getCursorCommentNo()));
            return commentProtoConverter.toListPostCommentsResponse(result);
        });
    }

    @Override
    public void listCommentReplies(
            ListCommentRepliesRequest request,
            StreamObserver<ListCommentRepliesResponse> responseObserver) {
        handle(responseObserver, () -> {
            CommentPageResult result = commentService.listCommentRepliesPage(
                    request.getRootCommentNo(),
                    request.getPageNo());
            return commentProtoConverter.toListCommentRepliesResponse(result);
        });
    }

    private Long normalizeParentCommentNo(long parentCommentNo) {
        return parentCommentNo <= 0 ? DatabaseSentinel.NONE_ID : parentCommentNo;
    }

    private Long normalizeCursorCommentNo(long cursorCommentNo) {
        return cursorCommentNo <= 0 ? DatabaseSentinel.NONE_ID : cursorCommentNo;
    }

    private String clientRequestId(String requestValue) {
        if (requestValue != null && !requestValue.isBlank()) {
            return requestValue;
        }
        return GrpcRequestContexts.currentRequestId();
    }

    private <T> void handle(StreamObserver<T> observer, GrpcCall<T> call) {
        try {
            observer.onNext(call.invoke());
            observer.onCompleted();
        } catch (Throwable throwable) {
            observer.onError(exceptionMapper.toStatusException(throwable));
        }
    }

    @FunctionalInterface
    private interface GrpcCall<T> {
        T invoke();
    }
}
