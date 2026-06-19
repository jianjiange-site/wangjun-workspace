package site.jianjiange.postservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import site.jianjiange.postservice.cache.FeedCache;
import site.jianjiange.postservice.cache.LikeCountCache;
import site.jianjiange.postservice.client.FeedUserClient;
import site.jianjiange.postservice.constant.DatabaseSentinel;
import site.jianjiange.postservice.entity.IdempotentRequestEntity;
import site.jianjiange.postservice.entity.PostEntity;
import site.jianjiange.postservice.enums.PostStatus;
import site.jianjiange.postservice.enums.UserGender;
import site.jianjiange.postservice.exception.BusinessException;
import site.jianjiange.postservice.mapper.IdempotentRequestMapper;
import site.jianjiange.postservice.mapper.PostImageMapper;
import site.jianjiange.postservice.mapper.PostMapper;
import site.jianjiange.postservice.service.command.GetFeedCommand;
import site.jianjiange.postservice.service.feed.FeedCursor;
import site.jianjiange.postservice.service.feed.FeedCursorCodec;
import site.jianjiange.postservice.service.result.FeedPageResult;
import site.jianjiange.postservice.service.result.PostResult;

/**
 * Feed 业务服务测试，覆盖三路召回、异性过滤、去重、混排和 cursor。
 */
@ActiveProfiles("test")
@SpringBootTest
class FeedServiceTest {

    @Autowired
    private FeedService feedService;

    @Autowired
    private FeedCursorCodec feedCursorCodec;

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private PostImageMapper postImageMapper;

    @Autowired
    private IdempotentRequestMapper idempotentRequestMapper;

    @MockBean
    private FeedCache feedCache;

    @MockBean
    private FeedUserClient feedUserClient;

    @MockBean
    private LikeCountCache likeCountCache;

    @BeforeEach
    void cleanDatabase() {
        idempotentRequestMapper.delete(new QueryWrapper<IdempotentRequestEntity>());
        postImageMapper.delete(new QueryWrapper<>());
        postMapper.delete(new QueryWrapper<>());
        reset(feedCache, feedUserClient, likeCountCache);
        when(feedCache.findCachedGender(anyLong())).thenReturn(Optional.empty());
        when(feedUserClient.findGender(1001L)).thenReturn(Optional.of(UserGender.FEMALE));
        when(feedCache.findNewCursor(eq(1001L), eq(UserGender.MALE))).thenReturn(Optional.empty());
        when(feedCache.findLikedAuthorLatestPostNos(anyCollection())).thenReturn(Map.of());
        when(feedCache.listHotPostNos(eq(UserGender.MALE), anyInt(), anyInt())).thenReturn(List.of());
        when(feedCache.listNewPostNosBefore(eq(UserGender.MALE), any(), anyInt())).thenReturn(List.of());
        when(feedCache.filterUnconsumedLikedLatestPosts(eq(1001L), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(likeCountCache.readLikeDeltas(anyCollection())).thenReturn(Map.of());
        doAnswer(invocation -> {
            Collection<Long> authorIds = invocation.getArgument(0);
            Map<Long, UserGender> genders = new LinkedHashMap<>();
            authorIds.forEach(authorId -> genders.put(authorId, UserGender.MALE));
            return genders;
        }).when(feedUserClient).findGenders(anyCollection());
    }

    @Test
    void getFeedMixesSourcesByFixedRatioAndDeduplicatesSamePage() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Long> hotPostNos = insertPosts(1000L, 16, 3000L, now.minusMinutes(1));
        List<Long> likedPostNos = insertPosts(2000L, 2, 4000L, now.minusMinutes(20));
        List<Long> newPostNos = insertPosts(3000L, 2, 5000L, now.minusMinutes(40));
        when(feedCache.listHotPostNos(eq(UserGender.MALE), anyInt(), anyInt())).thenReturn(hotPostNos);
        when(feedCache.listNewPostNosBefore(eq(UserGender.MALE), any(), anyInt())).thenReturn(newPostNos);
        when(feedUserClient.listLikedUserIds(1001L)).thenReturn(List.of(4001L, 4002L));
        Map<Long, Long> latestPostNosByAuthor = new LinkedHashMap<>();
        latestPostNosByAuthor.put(4001L, likedPostNos.get(0));
        latestPostNosByAuthor.put(4002L, likedPostNos.get(1));
        when(feedCache.findLikedAuthorLatestPostNos(List.of(4001L, 4002L))).thenReturn(latestPostNosByAuthor);

        FeedPageResult page = feedService.getFeed(new GetFeedCommand(1001L, "", false));

        List<Long> resultPostNos = page.posts().stream().map(PostResult::postNo).toList();
        assertThat(resultPostNos).hasSize(20).doesNotHaveDuplicates();
        assertThat(resultPostNos).containsAll(hotPostNos);
        assertThat(resultPostNos).containsAll(likedPostNos);
        assertThat(resultPostNos).containsAll(newPostNos);
        assertThat(page.pageSize()).isEqualTo(20);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
        verify(feedCache).markLikedConsumed(1001L, latestPostNosByAuthor);
        verify(feedCache).listHotPostNos(UserGender.MALE, 0, 20);
        verify(feedCache).listNewPostNosBefore(eq(UserGender.MALE), any(), eq(20));
        verify(feedCache, never()).deleteInvalidLikedAuthorLatestPosts(anyCollection());
    }

    @Test
    void getFeedDoesNotScanLikedAuthorsFromDatabaseWhenRedisMisses() {
        OffsetDateTime now = OffsetDateTime.now();
        insertPosts(2000L, 2, 4000L, now.minusDays(4));
        when(feedUserClient.listLikedUserIds(1001L)).thenReturn(List.of(4001L, 4002L));

        FeedPageResult page = feedService.getFeed(new GetFeedCommand(1001L, "", false));

        assertThat(page.posts()).isEmpty();
        verify(feedCache).findLikedAuthorLatestPostNos(List.of(4001L, 4002L));
    }

    @Test
    void getFeedFiltersLikedLatestPostConsumedByCurrentUserBeforeDatabaseLookup() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Long> likedPostNos = insertPosts(2000L, 2, 4000L, now.minusMinutes(20));
        when(feedUserClient.listLikedUserIds(1001L)).thenReturn(List.of(4001L, 4002L));
        when(feedCache.listHotPostNos(eq(UserGender.MALE), anyInt(), anyInt())).thenReturn(List.of(999901L));
        when(feedCache.listNewPostNosBefore(eq(UserGender.MALE), any(), anyInt())).thenReturn(List.of(999902L));
        Map<Long, Long> latestPostNosByAuthor = new LinkedHashMap<>();
        latestPostNosByAuthor.put(4001L, likedPostNos.get(0));
        latestPostNosByAuthor.put(4002L, likedPostNos.get(1));
        Map<Long, Long> unconsumedPostNosByAuthor = Map.of(4002L, likedPostNos.get(1));
        when(feedCache.findLikedAuthorLatestPostNos(List.of(4001L, 4002L))).thenReturn(latestPostNosByAuthor);
        doReturn(unconsumedPostNosByAuthor)
                .when(feedCache)
                .filterUnconsumedLikedLatestPosts(eq(1001L), eq(latestPostNosByAuthor));

        FeedPageResult page = feedService.getFeed(new GetFeedCommand(1001L, "", false));

        assertThat(page.posts()).extracting(PostResult::postNo).containsExactly(likedPostNos.get(1));
        verify(feedCache).filterUnconsumedLikedLatestPosts(1001L, latestPostNosByAuthor);
        verify(feedCache).markLikedConsumed(1001L, unconsumedPostNosByAuthor);
    }

    @Test
    void getFeedReturnsEmptyWhenCurrentGenderUnavailable() {
        when(feedUserClient.findGender(1001L)).thenReturn(Optional.empty());

        FeedPageResult page = feedService.getFeed(new GetFeedCommand(1001L, "", false));

        assertThat(page.posts()).isEmpty();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isEmpty();
    }

    @Test
    void getFeedSkipsCandidateWhenAuthorGenderMissing() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Long> hotPostNos = insertPosts(1000L, 2, 3000L, now.minusMinutes(1));
        when(feedCache.listHotPostNos(eq(UserGender.MALE), anyInt(), anyInt())).thenReturn(hotPostNos);
        when(feedCache.listNewPostNosBefore(eq(UserGender.MALE), any(), anyInt())).thenReturn(List.of());
        when(feedUserClient.listLikedUserIds(1001L)).thenReturn(List.of());
        when(feedUserClient.findGenders(anyCollection())).thenReturn(Map.of(3001L, UserGender.MALE));

        FeedPageResult page = feedService.getFeed(new GetFeedCommand(1001L, "", false));

        assertThat(page.posts()).extracting(PostResult::authorId).containsExactly(3001L);
    }

    @Test
    void getFeedRefreshClearsShortTermState() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Long> hotPostNos = insertPosts(1000L, 1, 3000L, now.minusMinutes(1));
        when(feedCache.listHotPostNos(eq(UserGender.MALE), anyInt(), anyInt())).thenReturn(hotPostNos);
        when(feedCache.listNewPostNosBefore(eq(UserGender.MALE), any(), anyInt())).thenReturn(List.of());
        when(feedUserClient.listLikedUserIds(1001L)).thenReturn(List.of());

        feedService.getFeed(new GetFeedCommand(1001L, "ignored", true));

        verify(feedCache).clearUserState(1001L, UserGender.MALE);
    }

    @Test
    void getFeedRejectsTamperedCursor() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Long> hotPostNos = insertPosts(1000L, 1, 3000L, now.minusMinutes(1));
        when(feedCache.listHotPostNos(eq(UserGender.MALE), anyInt(), anyInt())).thenReturn(hotPostNos);
        when(feedCache.listNewPostNosBefore(eq(UserGender.MALE), any(), anyInt())).thenReturn(List.of());
        when(feedUserClient.listLikedUserIds(1001L)).thenReturn(List.of());
        FeedPageResult page = feedService.getFeed(new GetFeedCommand(1001L, "", false));
        String tamperedCursor = page.nextCursor() + "x";

        assertThatThrownBy(() -> feedService.getFeed(new GetFeedCommand(1001L, tamperedCursor, false)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void getFeedReadsHotCandidatesByCursorOffset() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Long> firstPageHotPostNos = insertPosts(1000L, 20, 3000L, now.minusMinutes(1));
        List<Long> secondPageHotPostNos = insertPosts(2000L, 20, 4000L, now.minusMinutes(30));
        when(feedCache.listHotPostNos(eq(UserGender.MALE), eq(0), eq(20))).thenReturn(firstPageHotPostNos);
        when(feedCache.listHotPostNos(eq(UserGender.MALE), eq(20), eq(20))).thenReturn(secondPageHotPostNos);
        when(feedCache.listNewPostNosBefore(eq(UserGender.MALE), any(), anyInt())).thenReturn(List.of(999999L));
        when(feedUserClient.listLikedUserIds(1001L)).thenReturn(List.of());

        FeedPageResult firstPage = feedService.getFeed(new GetFeedCommand(1001L, "", false));
        FeedCursor firstCursor = feedCursorCodec.decode(firstPage.nextCursor()).orElseThrow();
        FeedPageResult secondPage = feedService.getFeed(new GetFeedCommand(1001L, firstPage.nextCursor(), false));

        assertThat(firstCursor.hotOffset()).isEqualTo(20);
        assertThat(secondPage.posts()).extracting(PostResult::postNo).containsExactlyElementsOf(secondPageHotPostNos);
        verify(feedCache).listHotPostNos(UserGender.MALE, 0, 20);
        verify(feedCache).listHotPostNos(UserGender.MALE, 20, 20);
    }

    @Test
    void getFeedTreatsExpiredCursorAsFirstPage() {
        OffsetDateTime now = OffsetDateTime.now();
        List<Long> hotPostNos = insertPosts(1000L, 1, 3000L, now.minusMinutes(1));
        when(feedCache.listHotPostNos(eq(UserGender.MALE), eq(0), eq(20))).thenReturn(hotPostNos);
        when(feedCache.listNewPostNosBefore(eq(UserGender.MALE), any(), anyInt())).thenReturn(List.of());
        when(feedUserClient.listLikedUserIds(1001L)).thenReturn(List.of());
        String expiredCursor = feedCursorCodec.encode(new FeedCursor(
                1001L,
                UserGender.MALE,
                now.minusMinutes(1),
                now.minusDays(1),
                80));

        FeedPageResult page = feedService.getFeed(new GetFeedCommand(1001L, expiredCursor, false));

        assertThat(page.posts()).extracting(PostResult::postNo).containsExactlyElementsOf(hotPostNos);
        verify(feedCache).listHotPostNos(UserGender.MALE, 0, 20);
    }

    @Test
    void rebuildHotCandidatesWritesGenderBuckets() {
        OffsetDateTime now = OffsetDateTime.now();
        insertPost(9001L, 7001L, 3001L, now.minusHours(1), 10L, 1L);
        insertPost(9002L, 7002L, 4001L, now.minusHours(1), 20L, 2L);
        when(likeCountCache.readLikeDeltas(anyCollection())).thenReturn(Map.of(7001L, 5L, 7002L, 1L));
        when(feedUserClient.findGenders(anyCollection())).thenReturn(Map.of(
                3001L, UserGender.MALE,
                4001L, UserGender.FEMALE));

        feedService.rebuildHotCandidates();

        ArgumentCaptor<Map<Long, Double>> maleScores = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<Long, Double>> femaleScores = ArgumentCaptor.forClass(Map.class);
        verify(feedCache).replaceHotPostNos(eq(UserGender.MALE), maleScores.capture());
        verify(feedCache).replaceHotPostNos(eq(UserGender.FEMALE), femaleScores.capture());
        assertThat(maleScores.getValue()).containsOnlyKeys(7001L);
        assertThat(femaleScores.getValue()).containsOnlyKeys(7002L);
    }

    @Test
    void rebuildHotCandidatesAddsBaseScoreOnlyWhenLatestPostCacheMatches() {
        OffsetDateTime now = OffsetDateTime.now();
        insertPost(9001L, 7001L, 3001L, now.minusHours(1), 0L, 0L);
        insertPost(9002L, 7002L, 3002L, now.minusHours(1), 0L, 0L);
        when(feedCache.findLikedAuthorLatestPostNos(anyCollection())).thenReturn(Map.of(3001L, 7001L));
        when(feedUserClient.findGenders(anyCollection())).thenReturn(Map.of(
                3001L, UserGender.MALE,
                3002L, UserGender.MALE));

        feedService.rebuildHotCandidates();

        ArgumentCaptor<Map<Long, Double>> maleScores = ArgumentCaptor.forClass(Map.class);
        verify(feedCache).replaceHotPostNos(eq(UserGender.MALE), maleScores.capture());
        assertThat(maleScores.getValue()).containsOnlyKeys(7001L, 7002L);
        assertThat(maleScores.getValue().get(7001L)).isGreaterThan(maleScores.getValue().get(7002L));
    }

    private List<Long> insertPosts(long firstPostNo, int count, long firstAuthorId, OffsetDateTime firstPublishedAt) {
        List<Long> postNos = new java.util.ArrayList<>();
        for (int index = 1; index <= count; index++) {
            long postNo = firstPostNo + index;
            insertPost(
                    postNo,
                    postNo,
                    firstAuthorId + index,
                    firstPublishedAt.minusMinutes(index),
                    0L,
                    0L);
            postNos.add(postNo);
        }
        return postNos;
    }

    private void insertPost(
            Long id,
            Long postNo,
            Long authorId,
            OffsetDateTime publishedAt,
            Long likeCount,
            Long commentCount) {
        PostEntity post = new PostEntity();
        post.setId(id);
        post.setPostNo(postNo);
        post.setAuthorId(authorId);
        post.setContent("post-" + postNo);
        post.setImageCount(0);
        post.setStatus(PostStatus.PUBLISHED);
        post.setLikeCount(likeCount);
        post.setCommentCount(commentCount);
        post.setPublishedAt(publishedAt);
        post.setDeletedAt(DatabaseSentinel.NONE_TIME);
        post.setVersion(0);
        post.setCreatedAt(publishedAt);
        post.setUpdatedAt(publishedAt);
        postMapper.insert(post);
    }
}
