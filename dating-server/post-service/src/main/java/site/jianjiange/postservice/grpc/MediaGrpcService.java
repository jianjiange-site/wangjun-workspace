package site.jianjiange.postservice.grpc;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.converter.GrpcTimeConverter;
import site.jianjiange.postservice.converter.ImageProtoConverter;
import site.jianjiange.postservice.proto.CreateImageUploadUrlRequest;
import site.jianjiange.postservice.proto.CreateImageUploadUrlResponse;
import site.jianjiange.postservice.proto.GetImageKeysRequest;
import site.jianjiange.postservice.proto.GetImageKeysResponse;
import site.jianjiange.postservice.proto.MediaServiceGrpc;
import site.jianjiange.postservice.service.MediaService;
import site.jianjiange.postservice.service.PostService;
import site.jianjiange.postservice.service.command.CreateImageUploadUrlCommand;
import site.jianjiange.postservice.service.result.CreateImageUploadUrlResult;
import site.jianjiange.postservice.service.result.PostResult;

/**
 * 图片媒体 gRPC 入口层。
 */
@Component
public class MediaGrpcService extends MediaServiceGrpc.MediaServiceImplBase {

    private final PostService postService;
    private final MediaService mediaService;
    private final ImageProtoConverter imageProtoConverter;
    private final GrpcExceptionMapper exceptionMapper;

    /**
     * 创建媒体 gRPC 入口。
     */
    public MediaGrpcService(
            PostService postService,
            MediaService mediaService,
            ImageProtoConverter imageProtoConverter,
            GrpcExceptionMapper exceptionMapper) {
        this.postService = postService;
        this.mediaService = mediaService;
        this.imageProtoConverter = imageProtoConverter;
        this.exceptionMapper = exceptionMapper;
    }

    @Override
    public void createImageUploadUrl(
            CreateImageUploadUrlRequest request,
            StreamObserver<CreateImageUploadUrlResponse> responseObserver) {
        handle(responseObserver, () -> {
            CreateImageUploadUrlResult result = mediaService.createImageUploadUrl(new CreateImageUploadUrlCommand(
                    GrpcRequestContexts.currentUserId(),
                    request.getContentType(),
                    request.getSizeBytes(),
                    request.getWidth(),
                    request.getHeight()));
            return CreateImageUploadUrlResponse.newBuilder()
                    .setImageId(result.imageNo())
                    .setObjectKey(result.objectKey())
                    .setUploadUrl(result.uploadUrl())
                    .setExpireAt(GrpcTimeConverter.toTimestamp(result.expireAt()))
                    .build();
        });
    }

    @Override
    public void getImageKeys(GetImageKeysRequest request, StreamObserver<GetImageKeysResponse> responseObserver) {
        handle(responseObserver, () -> {
            PostResult post = postService.getPostDetail(request.getPostNo())
                    .orElseThrow(() -> Status.NOT_FOUND
                            .withDescription("帖子不存在")
                            .asRuntimeException());
            return imageProtoConverter.toGetImageKeysResponse(post);
        });
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
