package site.jianjiange.postservice.service.result;

import java.time.OffsetDateTime;

/**
 * 创建图片上传 URL 结果。
 *
 * @param imageNo 图片业务号
 * @param objectKey 对象存储 key
 * @param uploadUrl 预签名上传 URL
 * @param expireAt 过期时间
 */
public record CreateImageUploadUrlResult(
        Long imageNo,
        String objectKey,
        String uploadUrl,
        OffsetDateTime expireAt
) {
}
