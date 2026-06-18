package site.jianjiange.postservice.service.feed;

import java.time.OffsetDateTime;
import site.jianjiange.postservice.enums.UserGender;

/**
 * Feed cursor 明文载荷，签名后返回给前端。
 *
 * @param userId 当前用户 ID
 * @param targetGender 当前页目标作者性别
 * @param expireAt cursor 过期时间
 * @param newBefore 下一页新帖召回时间游标
 * @param hotOffset 下一页热门候选读取偏移量
 */
public record FeedCursor(
        Long userId,
        UserGender targetGender,
        OffsetDateTime expireAt,
        OffsetDateTime newBefore,
        int hotOffset
) {
}
