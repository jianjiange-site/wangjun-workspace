package site.jianjiange.postservice.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import site.jianjiange.postservice.converter.CommentProtoConverter;
import site.jianjiange.postservice.converter.ImageProtoConverter;
import site.jianjiange.postservice.converter.PostProtoConverter;
import site.jianjiange.postservice.proto.CreateCommentRequest;
import site.jianjiange.postservice.proto.CreateCommentResponse;
import site.jianjiange.postservice.proto.CreateImageUploadUrlRequest;
import site.jianjiange.postservice.proto.CreateImageUploadUrlResponse;
import site.jianjiange.postservice.proto.CreatePostRequest;
import site.jianjiange.postservice.proto.CreatePostResponse;
import site.jianjiange.postservice.service.CommentService;
import site.jianjiange.postservice.service.FeedService;
import site.jianjiange.postservice.service.LikeService;
import site.jianjiange.postservice.service.MediaService;
import site.jianjiange.postservice.service.PostService;
import site.jianjiange.postservice.service.command.CreateCommentCommand;
import site.jianjiange.postservice.service.command.CreateImageUploadUrlCommand;
import site.jianjiange.postservice.service.command.CreatePostCommand;
import site.jianjiange.postservice.service.result.CreateCommentResult;
import site.jianjiange.postservice.service.result.CreateImageUploadUrlResult;
import site.jianjiange.postservice.service.result.CreatePostResult;

/**
 * gRPC 入口基础调用测试。
 */
class GrpcServiceTest {

    @Test
    void createPostReadsUserIdFromGrpcContext() {
        PostService postService = mock(PostService.class);
        when(postService.createPost(any())).thenReturn(new CreatePostResult(9001L, false));
        PostGrpcService grpcService = new PostGrpcService(
                postService,
                mock(FeedService.class),
                new PostProtoConverter(),
                new GrpcExceptionMapper());
        TestObserver<CreatePostResponse> observer = new TestObserver<>();

        withContext(1001L, "req-1", () -> grpcService.createPost(CreatePostRequest.newBuilder()
                .setContent("hello")
                .addImageId(7001L)
                .setClientRequestId("client-1")
                .build(), observer));

        ArgumentCaptor<CreatePostCommand> commandCaptor = ArgumentCaptor.forClass(CreatePostCommand.class);
        verify(postService).createPost(commandCaptor.capture());
        assertThat(commandCaptor.getValue().authorId()).isEqualTo(1001L);
        assertThat(commandCaptor.getValue().imageNos()).containsExactly(7001L);
        assertThat(observer.value().getPostNo()).isEqualTo(9001L);
        assertThat(observer.completed()).isTrue();
    }

    @Test
    void createCommentFallsBackToMetadataRequestIdAndNormalizesRootParent() {
        CommentService commentService = mock(CommentService.class);
        when(commentService.createComment(any())).thenReturn(new CreateCommentResult(8001L, false));
        InteractionGrpcService grpcService = new InteractionGrpcService(
                mock(LikeService.class),
                commentService,
                new CommentProtoConverter(),
                new GrpcExceptionMapper());
        TestObserver<CreateCommentResponse> observer = new TestObserver<>();

        withContext(1001L, "metadata-request", () -> grpcService.createComment(CreateCommentRequest.newBuilder()
                .setPostNo(9001L)
                .setContent("comment")
                .build(), observer));

        ArgumentCaptor<CreateCommentCommand> commandCaptor = ArgumentCaptor.forClass(CreateCommentCommand.class);
        verify(commentService).createComment(commandCaptor.capture());
        assertThat(commandCaptor.getValue().authorId()).isEqualTo(1001L);
        assertThat(commandCaptor.getValue().parentCommentNo()).isEqualTo(-1L);
        assertThat(commandCaptor.getValue().clientRequestId()).isEqualTo("metadata-request");
        assertThat(observer.value().getCommentNo()).isEqualTo(8001L);
        assertThat(observer.completed()).isTrue();
    }

    @Test
    void createImageUploadUrlReadsUserIdFromGrpcContext() {
        MediaService mediaService = mock(MediaService.class);
        when(mediaService.createImageUploadUrl(any())).thenReturn(new CreateImageUploadUrlResult(
                7001L,
                "wangjun-tmp/post/1001/7001.jpg",
                "http://localhost/upload",
                OffsetDateTime.now().plusMinutes(10)));
        MediaGrpcService grpcService = new MediaGrpcService(
                mock(PostService.class),
                mediaService,
                new ImageProtoConverter(),
                new GrpcExceptionMapper());
        TestObserver<CreateImageUploadUrlResponse> observer = new TestObserver<>();

        withContext(1001L, "req-upload", () -> grpcService.createImageUploadUrl(
                CreateImageUploadUrlRequest.newBuilder()
                        .setContentType("image/png")
                        .setSizeBytes(10L)
                        .build(),
                observer));

        ArgumentCaptor<CreateImageUploadUrlCommand> commandCaptor =
                ArgumentCaptor.forClass(CreateImageUploadUrlCommand.class);
        verify(mediaService).createImageUploadUrl(commandCaptor.capture());
        assertThat(commandCaptor.getValue().userId()).isEqualTo(1001L);
        assertThat(observer.value().getImageId()).isEqualTo(7001L);
        assertThat(observer.completed()).isTrue();
    }

    private void withContext(Long userId, String requestId, Runnable runnable) {
        Context context = Context.current().withValue(
                GrpcRequestContexts.REQUEST_CONTEXT_KEY,
                new GrpcRequestContext(userId, "USER", requestId));
        Context previous = context.attach();
        try {
            runnable.run();
        } finally {
            context.detach(previous);
        }
    }

    private static class TestObserver<T> implements StreamObserver<T> {

        private final AtomicReference<T> value = new AtomicReference<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private boolean completed;

        @Override
        public void onNext(T value) {
            this.value.set(value);
        }

        @Override
        public void onError(Throwable throwable) {
            error.set(throwable);
        }

        @Override
        public void onCompleted() {
            completed = true;
        }

        private T value() {
            return value.get();
        }

        private Throwable error() {
            return error.get();
        }

        private boolean completed() {
            return completed;
        }
    }
}
