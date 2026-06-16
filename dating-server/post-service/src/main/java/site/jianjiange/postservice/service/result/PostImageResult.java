package site.jianjiange.postservice.service.result;

/**
 * 帖子图片结果，暴露业务需要的图片元数据和对象存储 key。
 */
public record PostImageResult(
        Long imageNo,
        String bucket,
        String objectKey,
        String contentType,
        Long sizeBytes,
        Integer width,
        Integer height,
        Integer sortOrder) {
}
