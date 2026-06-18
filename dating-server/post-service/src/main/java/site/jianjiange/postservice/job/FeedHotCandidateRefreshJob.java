package site.jianjiange.postservice.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.service.FeedService;

/**
 * 热门 Feed 候选重建任务。
 */
@Component
public class FeedHotCandidateRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(FeedHotCandidateRefreshJob.class);

    private final FeedService feedService;

    /**
     * 创建热门 Feed 候选重建任务。
     *
     * @param feedService Feed 业务服务
     */
    public FeedHotCandidateRefreshJob(FeedService feedService) {
        this.feedService = feedService;
    }

    /**
     * 每 5 分钟重建一次热门候选缓存。
     */
    @Scheduled(initialDelayString = "${dating.feed.hot-rebuild-initial-delay-ms:60000}",
            fixedDelayString = "${dating.feed.hot-rebuild-fixed-delay-ms:300000}")
    public void rebuildHotCandidates() {
        try {
            feedService.rebuildHotCandidates();
        } catch (RuntimeException ex) {
            log.warn("热门 Feed 候选重建失败，errorType={}, errorMessage={}",
                    ex.getClass().getSimpleName(), ex.getMessage());
        }
    }
}
