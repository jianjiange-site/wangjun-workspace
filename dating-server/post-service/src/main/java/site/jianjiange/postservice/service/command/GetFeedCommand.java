package site.jianjiange.postservice.service.command;

/**
 * Feed 查询命令。
 *
 * @param userId 当前用户 ID
 * @param cursor 上一页返回的 cursor，首次查询可为空
 * @param refresh 是否主动刷新；刷新时重置短期翻页状态
 */
public record GetFeedCommand(
        Long userId,
        String cursor,
        boolean refresh
) {
}
