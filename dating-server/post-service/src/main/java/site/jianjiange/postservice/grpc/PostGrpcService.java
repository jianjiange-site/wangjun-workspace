package site.jianjiange.postservice.grpc;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import net.devh.boot.grpc.server.service.GrpcService;
import site.jianjiange.postservice.converter.PostProtoConverter;
import site.jianjiange.postservice.proto.CreatePostRequest;
import site.jianjiange.postservice.proto.CreatePostResponse;
import site.jianjiange.postservice.proto.DeletePostRequest;
import site.jianjiange.postservice.proto.GetFeedRequest;
import site.jianjiange.postservice.proto.GetFeedResponse;
import site.jianjiange.postservice.proto.GetPostRequest;
import site.jianjiange.postservice.proto.GetPostResponse;
import site.jianjiange.postservice.proto.ListAuthorPostsRequest;
import site.jianjiange.postservice.proto.ListAuthorPostsResponse;
import site.jianjiange.postservice.proto.PostServiceGrpc;
import site.jianjiange.postservice.service.FeedService;
import site.jianjiange.postservice.service.PostService;
import site.jianjiange.postservice.service.command.CreatePostCommand;
import site.jianjiange.postservice.service.command.GetFeedCommand;
import site.jianjiange.postservice.service.result.CreatePostResult;
import site.jianjiange.postservice.service.result.FeedPageResult;
import site.jianjiange.postservice.service.result.PostResult;

/**
 * 帖子和 Feed gRPC 入口层。
 */
@GrpcService
public class PostGrpcService extends PostServiceGrpc.PostServiceImplBase {

    private final PostService postService;
    private final FeedService feedService;
    private final PostProtoConverter postProtoConverter;
    private final GrpcExceptionMapper exceptionMapper;

    /**
     * 创建帖子 gRPC 入口。
     */
    public PostGrpcService(
            PostService postService,
            FeedService feedService,
            PostProtoConverter postProtoConverter,
            GrpcExceptionMapper exceptionMapper) {
        this.postService = postService;
        this.feedService = feedService;
        this.postProtoConverter = postProtoConverter;
        this.exceptionMapper = exceptionMapper;
    }

    @Override
    public void createPost(CreatePostRequest request, StreamObserver<CreatePostResponse> responseObserver) {
        handle(responseObserver, () -> {
            CreatePostResult result = postService.createPost(new CreatePostCommand(
                    GrpcRequestContexts.currentUserId(),
                    request.getContent(),
                    request.getImageIdList(),
                    clientRequestId(request.getClientRequestId())));
            return CreatePostResponse.newBuilder()
                    .setPostNo(result.postNo())
                    .setDuplicated(result.duplicated())
                    .build();
        });
    }

    @Override
    public void deletePost(DeletePostRequest request, StreamObserver<Empty> responseObserver) {
        handle(responseObserver, () -> {
            postService.deletePost(GrpcRequestContexts.currentUserId(), request.getPostNo());
            return Empty.getDefaultInstance();
        });
    }

    @Override
    public void getPost(GetPostRequest request, StreamObserver<GetPostResponse> responseObserver) {
        handle(responseObserver, () -> {
            PostResult post = postService.getPostDetail(request.getPostNo())
                    .orElseThrow(() -> Status.NOT_FOUND
                            .withDescription("帖子不存在")
                            .asRuntimeException());
            return GetPostResponse.newBuilder()
                    .setPost(postProtoConverter.toPost(post))
                    .build();
        });
    }

    @Override
    public void listAuthorPosts(
            ListAuthorPostsRequest request,
            StreamObserver<ListAuthorPostsResponse> responseObserver) {
        handle(responseObserver, () -> {
            List<PostResult> posts = postService.listAuthorPosts(request.getAuthorId(), request.getPageSize());
            return postProtoConverter.toListAuthorPostsResponse(posts, request.getPageSize());
        });
    }

    @Override
    public void getFeed(GetFeedRequest request, StreamObserver<GetFeedResponse> responseObserver) {
        handle(responseObserver, () -> {
            FeedPageResult page = feedService.getFeed(new GetFeedCommand(
                    GrpcRequestContexts.currentUserId(),
                    request.getCursor(),
                    request.getRefresh()));
            return postProtoConverter.toFeedResponse(page);
        });
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
