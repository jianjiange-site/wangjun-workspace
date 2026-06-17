package site.jianjiange.postservice.service.result;

/**
 * 点赞帖子结果。
 *
 * @param postNo 帖子业务号
 * @param duplicated 是否重复点赞
 */
public record LikePostResult(
        Long postNo,
        boolean duplicated
) {
}
