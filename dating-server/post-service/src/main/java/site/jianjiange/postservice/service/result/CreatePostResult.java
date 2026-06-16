package site.jianjiange.postservice.service.result;

/**
 * 创建帖子结果，返回帖子业务号以及是否命中幂等记录。
 */
public record CreatePostResult(Long postNo, boolean duplicated) {
}
