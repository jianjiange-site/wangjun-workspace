package site.jianjiange.postservice.service.feed;

/**
 * Feed 召回候选。
 *
 * @param postNo 帖子业务号
 * @param source 候选来源
 * @param authorId 作者 ID；喜欢过的人来源需要用于用户级消费标记
 */
public record FeedCandidate(Long postNo, FeedSource source, Long authorId) {

    public FeedCandidate(Long postNo, FeedSource source) {
        this(postNo, source, null);
    }
}
