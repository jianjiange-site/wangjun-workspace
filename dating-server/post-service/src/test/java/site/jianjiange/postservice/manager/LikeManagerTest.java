package site.jianjiange.postservice.manager;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import site.jianjiange.postservice.entity.PostEntity;
import site.jianjiange.postservice.enums.PostStatus;
import site.jianjiange.postservice.mapper.PostMapper;

/**
 * 点赞数据管理器测试，覆盖点赞计数累加。
 */
@ActiveProfiles("test")
@SpringBootTest
class LikeManagerTest {

    @Autowired
    private LikeManager likeManager;

    @Autowired
    private PostMapper postMapper;

    /**
     * 清理帖子，保证每个用例独立。
     */
    @BeforeEach
    void cleanDatabase() {
        postMapper.delete(new QueryWrapper<>());
    }

    /**
     * 验证点赞计数按 delta 累加。
     */
    @Test
    void increaseLikeCountAddsDeltaToPost() {
        insertPost(1001L, 91001L, 2001L);

        boolean updated = likeManager.increaseLikeCount(91001L, 3L);

        assertThat(updated).isTrue();
        assertThat(postMapper.selectById(1001L).getLikeCount()).isEqualTo(3L);
    }

    /**
     * 验证非正数 delta 不更新数据库但视为成功跳过。
     */
    @Test
    void increaseLikeCountSkipsNonPositiveDelta() {
        insertPost(1001L, 91001L, 2001L);

        boolean updated = likeManager.increaseLikeCount(91001L, 0L);

        assertThat(updated).isTrue();
        assertThat(postMapper.selectById(1001L).getLikeCount()).isZero();
    }

    /**
     * 验证帖子不存在时计数更新失败。
     */
    @Test
    void increaseLikeCountReturnsFalseWhenPostMissing() {
        boolean updated = likeManager.increaseLikeCount(91001L, 1L);

        assertThat(updated).isFalse();
    }

    /**
     * 插入用于测试的帖子。
     *
     * @param id 帖子技术主键
     * @param postNo 帖子业务号
     * @param authorId 作者 ID
     */
    private void insertPost(Long id, Long postNo, Long authorId) {
        OffsetDateTime now = OffsetDateTime.now();
        PostEntity post = new PostEntity();
        post.setId(id);
        post.setPostNo(postNo);
        post.setAuthorId(authorId);
        post.setContent("hello post");
        post.setImageCount(0);
        post.setStatus(PostStatus.PUBLISHED);
        post.setLikeCount(0L);
        post.setCommentCount(0L);
        post.setPublishedAt(now);
        post.setVersion(0);
        post.setCreatedAt(now);
        post.setUpdatedAt(now);
        postMapper.insert(post);
    }
}
