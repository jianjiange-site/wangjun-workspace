package site.jianjiange.postservice.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.entity.PostEntity;
import site.jianjiange.postservice.enums.PostStatus;
import site.jianjiange.postservice.mapper.PostMapper;

/**
 * Feed 数据管理器，封装 Feed 召回和回库查询。
 */
@Component
public class FeedManager {

    private final PostMapper postMapper;

    /**
     * 创建 Feed 数据管理器。
     *
     * @param postMapper 帖子 Mapper
     */
    public FeedManager(PostMapper postMapper) {
        this.postMapper = postMapper;
    }

    /**
     * 按帖子业务号批量查询公开可见帖子。
     *
     * @param postNos 帖子业务号集合
     * @return 公开可见帖子列表
     */
    public List<PostEntity> listPublishedByPostNos(Collection<Long> postNos) {
        if (postNos == null || postNos.isEmpty()) {
            return List.of();
        }
        return postMapper.selectList(new LambdaQueryWrapper<PostEntity>()
                .in(PostEntity::getPostNo, postNos)
                .eq(PostEntity::getStatus, PostStatus.PUBLISHED));
    }

    /**
     * 查询最近窗口内公开帖子，用于热门缓存重建或缓存不可用兜底。
     *
     * @param since 起始发布时间
     * @param limit 最大返回数量
     * @return 最新公开帖子列表
     */
    public List<PostEntity> listRecentPublishedSince(OffsetDateTime since, int limit) {
        return postMapper.selectList(new LambdaQueryWrapper<PostEntity>()
                .eq(PostEntity::getStatus, PostStatus.PUBLISHED)
                .ge(PostEntity::getPublishedAt, since)
                .orderByDesc(PostEntity::getPublishedAt, PostEntity::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }

    /**
     * 查询早于时间游标的最近公开帖子，用于新帖缓存不可用兜底。
     *
     * @param beforeTime 时间游标
     * @param since 起始发布时间
     * @param limit 最大返回数量
     * @return 早于游标的公开帖子列表
     */
    public List<PostEntity> listRecentPublishedBefore(OffsetDateTime beforeTime, OffsetDateTime since, int limit) {
        return postMapper.selectList(new LambdaQueryWrapper<PostEntity>()
                .eq(PostEntity::getStatus, PostStatus.PUBLISHED)
                .lt(PostEntity::getPublishedAt, beforeTime)
                .ge(PostEntity::getPublishedAt, since)
                .orderByDesc(PostEntity::getPublishedAt, PostEntity::getId)
                .last("LIMIT " + Math.max(1, limit)));
    }
}
