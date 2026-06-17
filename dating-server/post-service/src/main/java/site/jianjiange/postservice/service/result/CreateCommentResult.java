package site.jianjiange.postservice.service.result;

/**
 * 创建评论结果，返回评论业务号以及是否命中幂等记录。
 */
public record CreateCommentResult(Long commentNo, boolean duplicated) {
}
