package site.jianjiange.postservice.converter;

import org.springframework.stereotype.Component;
import site.jianjiange.postservice.proto.GetImageKeysResponse;
import site.jianjiange.postservice.proto.ImageKey;
import site.jianjiange.postservice.service.result.PostResult;

/**
 * 图片业务结果到 proto 的转换器。
 */
@Component
public class ImageProtoConverter {

    /**
     * 转换帖子图片 key 响应。
     *
     * @param post 帖子详情业务结果
     * @return proto 响应
     */
    public GetImageKeysResponse toGetImageKeysResponse(PostResult post) {
        GetImageKeysResponse.Builder builder = GetImageKeysResponse.newBuilder();
        if (post.images() == null) {
            return builder.build();
        }
        post.images().forEach(image -> builder.addImages(ImageKey.newBuilder()
                .setImageId(image.imageNo())
                .setBucket(image.bucket())
                .setObjectKey(image.objectKey())
                .setSortOrder(image.sortOrder())
                .build()));
        return builder.build();
    }
}
