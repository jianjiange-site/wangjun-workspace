package site.jianjiange.postservice.service.feed;

/**
 * Feed 候选来源；数值越小优先级越高。
 */
public enum FeedSource {

    LIKED(0),
    HOT(1),
    NEW(2);

    private final int priority;

    FeedSource(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }
}
