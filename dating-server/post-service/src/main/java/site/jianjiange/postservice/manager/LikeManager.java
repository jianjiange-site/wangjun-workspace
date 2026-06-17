package site.jianjiange.postservice.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import site.jianjiange.postservice.entity.PostEntity;
import site.jianjiange.postservice.mapper.PostMapper;

/**
 * 点赞数据管理器，封装帖子查询和点赞计数累加。
 */
@Component
public class LikeManager {

    private final PostMapper postMapper;

    /**
     * 创建点赞数据管理器。
     *
     * @param postMapper 帖子 Mapper
     */
    public LikeManager(PostMapper postMapper) {
        this.postMapper = postMapper;
    }

    /**
     * 根据帖子业务号查询帖子。
     *
     * @param postNo 帖子业务号
     * @return 帖子实体，未命中时返回 null
     */
    public PostEntity findPostByPostNo(Long postNo) {
        return postMapper.selectOne(new LambdaQueryWrapper<PostEntity>()
                .eq(PostEntity::getPostNo, postNo));
    }

    /**
     * 按帖子业务号累加 PostgreSQL 基准点赞数。
     *
     * @param postNo 帖子业务号
     * @param delta 点赞增量
     * @return 更新成功返回 true
     */
    public boolean increaseLikeCount(Long postNo, long delta) {
        if (delta <= 0) {
            return true;
        }
        return postMapper.update(null, new LambdaUpdateWrapper<PostEntity>()
                .eq(PostEntity::getPostNo, postNo)
                .setSql("like_count = like_count + " + delta)
                .set(PostEntity::getUpdatedAt, OffsetDateTime.now())) == 1;
    }
}
