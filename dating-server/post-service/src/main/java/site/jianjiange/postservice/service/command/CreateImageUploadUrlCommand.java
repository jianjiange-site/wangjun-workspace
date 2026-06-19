package site.jianjiange.postservice.service.command;

/**
 * 创建图片上传 URL 命令。
 *
 * @param userId 当前用户 ID
 * @param contentType 图片内容类型
 * @param sizeBytes 图片大小
 * @param width 图片宽度；未知时传 -1
 * @param height 图片高度；未知时传 -1
 */
public record CreateImageUploadUrlCommand(
        Long userId,
        String contentType,
        Long sizeBytes,
        Integer width,
        Integer height
) {
}
