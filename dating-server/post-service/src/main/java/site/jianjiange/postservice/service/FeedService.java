package site.jianjiange.postservice.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.jianjiange.postservice.cache.FeedCache;
import site.jianjiange.postservice.cache.LikeCountCache;
import site.jianjiange.postservice.client.FeedUserClient;
import site.jianjiange.postservice.entity.PostEntity;
import site.jianjiange.postservice.entity.PostImageEntity;
import site.jianjiange.postservice.enums.UserGender;
import site.jianjiange.postservice.exception.BusinessException;
import site.jianjiange.postservice.exception.PostErrorCode;
import site.jianjiange.postservice.manager.FeedManager;
import site.jianjiange.postservice.manager.PostImageManager;
import site.jianjiange.postservice.service.command.GetFeedCommand;
import site.jianjiange.postservice.service.feed.FeedCandidate;
import site.jianjiange.postservice.service.feed.FeedCursor;
import site.jianjiange.postservice.service.feed.FeedCursorCodec;
import site.jianjiange.postservice.service.feed.FeedSource;
import site.jianjiange.postservice.service.result.FeedPageResult;
import site.jianjiange.postservice.service.result.PostImageResult;
import site.jianjiange.postservice.service.result.PostResult;

/**
 * Feed 业务服务，实现热门、喜欢过的人和新帖三路召回混排。
 */
@Service
public class FeedService {

    private static final Logger log = LoggerFactory.getLogger(FeedService.class);
    private static final int PAGE_SIZE = 20;
    private static final int HOT_QUOTA = (int) (PAGE_SIZE * 0.8);
    private static final int LIKED_QUOTA = (int) (PAGE_SIZE * 0.1);
    private static final int NEW_QUOTA = (int) (PAGE_SIZE * 0.1);
    private static final int HOT_RECALL_LIMIT = 20;
    private static final int NEW_RECALL_LIMIT = 20;
    private static final int HOT_CACHE_LIMIT = 500;
    private static final int HOT_REBUILD_SCAN_LIMIT = 2000;
    private static final int SAME_AUTHOR_LIMIT = 2;
    private static final Duration FEED_CURSOR_TTL = Duration.ofMinutes(6);
    private static final Duration HOT_WINDOW = Duration.ofDays(3);
    private static final double HOT_BASE_WEIGHT = 10.0D;
    private static final double HOT_LIKE_WEIGHT = 1.0D;
    private static final double HOT_COMMENT_WEIGHT = 3.0D;

    private final FeedManager feedManager;
    private final PostImageManager postImageManager;
    private final LikeCountCache likeCountCache;
    private final FeedCache feedCache;
    private final FeedUserClient feedUserClient;
    private final FeedCursorCodec feedCursorCodec;

    /**
     * 创建 Feed 业务服务。
     *
     * @param feedManager Feed 数据管理器
     * @param postImageManager 帖子图片管理器
     * @param likeCountCache 点赞计数缓存
     * @param feedCache Feed 缓存
     * @param feedUserClient 用户侧端口
     * @param feedCursorCodec cursor 编解码器
     */
    public FeedService(
            FeedManager feedManager,
            PostImageManager postImageManager,
            LikeCountCache likeCountCache,
            FeedCache feedCache,
            FeedUserClient feedUserClient,
            FeedCursorCodec feedCursorCodec) {
        this.feedManager = feedManager;
        this.postImageManager = postImageManager;
        this.likeCountCache = likeCountCache;
        this.feedCache = feedCache;
        this.feedUserClient = feedUserClient;
        this.feedCursorCodec = feedCursorCodec;
    }

    /**
     * 获取 Feed。
     *
     * @param command Feed 查询命令
     * @return Feed 分页结果
     */
    @Transactional(readOnly = true)
    public FeedPageResult getFeed(GetFeedCommand command) {
        validateCommand(command);
        Optional<UserGender> currentGender = resolveCurrentGender(command.userId());
        if (currentGender.isEmpty()) {
            return emptyPage();
        }
        UserGender targetGender = currentGender.orElseThrow().opposite();
        OffsetDateTime now = OffsetDateTime.now();
        Optional<FeedCursor> cursor = resolveCursor(command, targetGender, now);
        int hotOffset = resolveHotOffset(command, cursor);
        //读取新帖的时间游标
        OffsetDateTime newBefore = resolveNewBefore(command, targetGender, cursor, now);
        if (command.refresh()) {
            clearUserState(command.userId(), targetGender);
        }

        List<FeedCandidate> candidates = recallCandidates(command.userId(), targetGender, hotOffset, newBefore, now);
        List<FeedCandidate> filteredCandidates = filterCandidates(command.userId(), targetGender, candidates);
        List<FeedCandidate> selectedCandidates = selectCandidates(filteredCandidates);
        List<PostResult> results = toResults(selectedCandidates);
        OffsetDateTime nextNewBefore = nextNewBefore(selectedCandidates, newBefore);
        int nextHotOffset = hotOffset + HOT_RECALL_LIMIT;
        saveUserState(command.userId(), targetGender, selectedCandidates, results, nextNewBefore);
        String nextCursor = feedCursorCodec.encode(new FeedCursor(
                command.userId(),
                targetGender,
                now.plus(FEED_CURSOR_TTL),
                nextNewBefore,
                nextHotOffset));
        return new FeedPageResult(results, nextCursor, PAGE_SIZE, results.size() == PAGE_SIZE);
    }

    /**
     * 重建热门候选缓存。
     */
    @Transactional(readOnly = true)
    public void rebuildHotCandidates() {
        OffsetDateTime now = OffsetDateTime.now();
        List<PostEntity> posts = feedManager.listRecentPublishedSince(now.minus(HOT_WINDOW), HOT_REBUILD_SCAN_LIMIT);
        if (posts.isEmpty()) {
            feedCache.replaceHotPostNos(UserGender.MALE, Map.of());
            feedCache.replaceHotPostNos(UserGender.FEMALE, Map.of());
            return;
        }
        Map<Long, Long> likeDeltas = readLikeDeltas(posts.stream().map(PostEntity::getPostNo).toList());
        Set<Long> authorIds = posts.stream().map(PostEntity::getAuthorId).collect(Collectors.toSet());
        Map<Long, Long> latestPostNosByAuthor = findLatestPostNosForHotBaseScore(authorIds);
        Map<Long, UserGender> gendersByAuthor = resolveAuthorGenders(authorIds);
        Map<UserGender, Map<Long, Double>> scoresByGender = new EnumMap<>(UserGender.class);
        scoresByGender.put(UserGender.MALE, new LinkedHashMap<>());
        scoresByGender.put(UserGender.FEMALE, new LinkedHashMap<>());
        for (PostEntity post : posts) {
            UserGender gender = gendersByAuthor.get(post.getAuthorId());
            if (gender == null) {
                continue;
            }
            boolean hasBaseScore = Objects.equals(latestPostNosByAuthor.get(post.getAuthorId()), post.getPostNo());
            double score = hotScore(post, likeDeltas.getOrDefault(post.getPostNo(), 0L), now, hasBaseScore);
            scoresByGender.get(gender).put(post.getPostNo(), score);
        }
        scoresByGender.forEach((gender, scores) -> feedCache.replaceHotPostNos(
                gender,
                scores.entrySet().stream()
                        .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                        .limit(HOT_CACHE_LIMIT)
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (left, right) -> left,
                                LinkedHashMap::new))));
    }

    private List<FeedCandidate> recallCandidates(
            Long userId,
            UserGender targetGender,
            int hotOffset,
            OffsetDateTime newBefore,
            OffsetDateTime now) {
        List<FeedCandidate> liked = recallLikedCandidates(userId);
        List<FeedCandidate> hot = recallHotCandidates(targetGender, hotOffset, now);
        List<FeedCandidate> fresh = recallNewCandidates(targetGender, newBefore, now);

        Map<Long, FeedCandidate> candidatesByPostNo = new LinkedHashMap<>();
        addCandidatesByPriority(candidatesByPostNo, liked);
        addCandidatesByPriority(candidatesByPostNo, hot);
        addCandidatesByPriority(candidatesByPostNo, fresh);
        return new ArrayList<>(candidatesByPostNo.values());
    }

    private List<FeedCandidate> recallLikedCandidates(Long userId) {
        List<Long> likedUserIds = listLikedUserIds(userId);
        if (likedUserIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> latestPostNosByAuthor = findLikedAuthorLatestPostNos(userId, likedUserIds);
        if (latestPostNosByAuthor.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> unconsumedPostNosByAuthor = filterUnconsumedLikedLatestPosts(userId, latestPostNosByAuthor);
        if (unconsumedPostNosByAuthor.isEmpty()) {
            return List.of();
        }
        List<Long> postNos = unconsumedPostNosByAuthor.values().stream()
                .distinct()
                .toList();
        Map<Long, PostEntity> postsByNo = feedManager.listPublishedByPostNos(postNos).stream()
                .collect(Collectors.toMap(PostEntity::getPostNo, Function.identity()));
        deleteInvalidLikedAuthorLatestPosts(userId, unconsumedPostNosByAuthor.entrySet().stream()
                .filter(entry -> !postsByNo.containsKey(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList());
        return unconsumedPostNosByAuthor.entrySet().stream()
                .filter(entry -> postsByNo.containsKey(entry.getValue()))
                .map(entry -> new FeedCandidate(entry.getValue(), FeedSource.LIKED, entry.getKey()))
                .toList();
    }

    private List<FeedCandidate> recallHotCandidates(UserGender targetGender, int hotOffset, OffsetDateTime now) {
        try {
            List<Long> cachedPostNos = feedCache.listHotPostNos(targetGender, hotOffset, HOT_RECALL_LIMIT);
            if (!cachedPostNos.isEmpty()) {
                return cachedPostNos.stream()
                        .map(postNo -> new FeedCandidate(postNo, FeedSource.HOT))
                        .toList();
            }
        } catch (RuntimeException ex) {
            log.warn("读取热门 Feed 缓存失败，targetGender={}, errorType={}, errorMessage={}",
                    targetGender, ex.getClass().getSimpleName(), ex.getMessage());
        }
        if (hotOffset > 0) {
            return List.of();
        }
        return feedManager.listRecentPublishedSince(now.minus(HOT_WINDOW), HOT_RECALL_LIMIT).stream()
                .map(post -> new FeedCandidate(post.getPostNo(), FeedSource.HOT))
                .toList();
    }

    private List<FeedCandidate> recallNewCandidates(
            UserGender targetGender,
            OffsetDateTime newBefore,
            OffsetDateTime now) {
        try {
            List<Long> cachedPostNos = feedCache.listNewPostNosBefore(targetGender, newBefore, NEW_RECALL_LIMIT);
            if (!cachedPostNos.isEmpty()) {
                return cachedPostNos.stream()
                        .map(postNo -> new FeedCandidate(postNo, FeedSource.NEW))
                        .toList();
            }
        } catch (RuntimeException ex) {
            log.warn("读取新帖 Feed 缓存失败，targetGender={}, errorType={}, errorMessage={}",
                    targetGender, ex.getClass().getSimpleName(), ex.getMessage());
        }
        return feedManager.listRecentPublishedBefore(newBefore, now.minus(HOT_WINDOW), NEW_RECALL_LIMIT).stream()
                .map(post -> new FeedCandidate(post.getPostNo(), FeedSource.NEW))
                .toList();
    }

    private void addCandidatesByPriority(Map<Long, FeedCandidate> candidatesByPostNo, List<FeedCandidate> candidates) {
        for (FeedCandidate candidate : candidates) {
            FeedCandidate existing = candidatesByPostNo.get(candidate.postNo());
            if (existing == null || candidate.source().priority() < existing.source().priority()) {
                candidatesByPostNo.put(candidate.postNo(), candidate);
            }
        }
    }

    private List<FeedCandidate> filterCandidates(
            Long userId,
            UserGender targetGender,
            List<FeedCandidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Long> orderedPostNos = candidates.stream()
                .map(FeedCandidate::postNo)
                .distinct()
                .toList();
        Map<Long, PostEntity> postsByNo = feedManager.listPublishedByPostNos(orderedPostNos).stream()
                .collect(Collectors.toMap(PostEntity::getPostNo, Function.identity()));
        Map<Long, UserGender> gendersByAuthor = resolveAuthorGenders(postsByNo.values().stream()
                .map(PostEntity::getAuthorId)
                .collect(Collectors.toSet()));
        List<FeedCandidate> filtered = new ArrayList<>();
        for (FeedCandidate candidate : candidates) {
            PostEntity post = postsByNo.get(candidate.postNo());
            if (post == null || userId.equals(post.getAuthorId())) {
                continue;
            }
            UserGender authorGender = gendersByAuthor.get(post.getAuthorId());
            if (authorGender == targetGender) {
                filtered.add(candidate);
            }
        }
        return filtered;
    }

    private List<FeedCandidate> selectCandidates(List<FeedCandidate> candidates) {
        Map<FeedSource, List<FeedCandidate>> bySource = new EnumMap<>(FeedSource.class);
        bySource.put(FeedSource.HOT, new ArrayList<>());
        bySource.put(FeedSource.LIKED, new ArrayList<>());
        bySource.put(FeedSource.NEW, new ArrayList<>());
        for (FeedCandidate candidate : candidates) {
            bySource.get(candidate.source()).add(candidate);
        }

        List<FeedCandidate> selected = new ArrayList<>();
        Set<Long> selectedPostNos = new HashSet<>();
        Map<Long, Integer> authorCounts = new HashMap<>();
        Map<Long, Long> authorByPostNo = loadAuthorByPostNo(candidates);
        List<FeedSource> pattern = feedPattern();
        Map<FeedSource, Integer> sourceLimits = Map.of(
                FeedSource.HOT, HOT_QUOTA,
                FeedSource.LIKED, LIKED_QUOTA,
                FeedSource.NEW, NEW_QUOTA);
        Map<FeedSource, Integer> selectedBySource = new EnumMap<>(FeedSource.class);
        selectedBySource.put(FeedSource.HOT, 0);
        selectedBySource.put(FeedSource.LIKED, 0);
        selectedBySource.put(FeedSource.NEW, 0);
        for (FeedSource source : pattern) {
            if (selected.size() >= PAGE_SIZE) {
                break;
            }
            if (selectedBySource.get(source) >= sourceLimits.get(source)) {
                continue;
            }
            Optional<FeedCandidate> picked = pickNext(
                    bySource.get(source), selectedPostNos, authorCounts, authorByPostNo, true);
            picked.ifPresent(candidate -> {
                addSelected(selected, selectedPostNos, authorCounts, authorByPostNo, candidate);
                selectedBySource.compute(source, (key, value) -> value == null ? 1 : value + 1);
            });
        }

        fillCandidates(selected, selectedPostNos, authorCounts, authorByPostNo, bySource, true);
        if (selected.size() < PAGE_SIZE) {
            fillCandidates(selected, selectedPostNos, authorCounts, authorByPostNo, bySource, false);
        }
        return selected;
    }

    private void fillCandidates(
            List<FeedCandidate> selected,
            Set<Long> selectedPostNos,
            Map<Long, Integer> authorCounts,
            Map<Long, Long> authorByPostNo,
            Map<FeedSource, List<FeedCandidate>> bySource,
            boolean enforceAuthorLimit) {
        for (FeedSource source : List.of(FeedSource.NEW, FeedSource.HOT, FeedSource.LIKED)) {
            while (selected.size() < PAGE_SIZE) {
                Optional<FeedCandidate> picked = pickNext(
                        bySource.get(source), selectedPostNos, authorCounts, authorByPostNo, enforceAuthorLimit);
                if (picked.isEmpty()) {
                    break;
                }
                addSelected(selected, selectedPostNos, authorCounts, authorByPostNo, picked.orElseThrow());
            }
        }
    }

    private Optional<FeedCandidate> pickNext(
            List<FeedCandidate> candidates,
            Set<Long> selectedPostNos,
            Map<Long, Integer> authorCounts,
            Map<Long, Long> authorByPostNo,
            boolean enforceAuthorLimit) {
        for (FeedCandidate candidate : candidates) {
            if (selectedPostNos.contains(candidate.postNo())) {
                continue;
            }
            Long authorId = authorByPostNo.get(candidate.postNo());
            if (authorId == null) {
                continue;
            }
            if (enforceAuthorLimit && authorCounts.getOrDefault(authorId, 0) >= SAME_AUTHOR_LIMIT) {
                continue;
            }
            return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private void addSelected(
            List<FeedCandidate> selected,
            Set<Long> selectedPostNos,
            Map<Long, Integer> authorCounts,
            Map<Long, Long> authorByPostNo,
            FeedCandidate candidate) {
        selected.add(candidate);
        selectedPostNos.add(candidate.postNo());
        Long authorId = authorByPostNo.get(candidate.postNo());
        if (authorId != null) {
            authorCounts.compute(authorId, (key, value) -> value == null ? 1 : value + 1);
        }
    }

    private Map<Long, Long> loadAuthorByPostNo(List<FeedCandidate> candidates) {
        List<Long> postNos = candidates.stream()
                .map(FeedCandidate::postNo)
                .distinct()
                .toList();
        return feedManager.listPublishedByPostNos(postNos).stream()
                .collect(Collectors.toMap(PostEntity::getPostNo, PostEntity::getAuthorId));
    }

    private List<FeedSource> feedPattern() {
        List<FeedSource> pattern = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            for (int index = 0; index < 8; index++) {
                pattern.add(FeedSource.HOT);
            }
            pattern.add(FeedSource.LIKED);
            pattern.add(FeedSource.NEW);
        }
        return pattern;
    }

    private List<PostResult> toResults(List<FeedCandidate> selectedCandidates) {
        if (selectedCandidates.isEmpty()) {
            return List.of();
        }
        List<Long> postNos = selectedCandidates.stream()
                .map(FeedCandidate::postNo)
                .toList();
        Map<Long, PostEntity> postsByNo = feedManager.listPublishedByPostNos(postNos).stream()
                .collect(Collectors.toMap(PostEntity::getPostNo, Function.identity()));
        Map<Long, List<PostImageEntity>> imagesByPostNo = postImageManager.listBoundImagesByPostNos(postNos);
        Map<Long, Long> likeDeltas = readLikeDeltas(postNos);
        return selectedCandidates.stream()
                .map(FeedCandidate::postNo)
                .map(postsByNo::get)
                .filter(post -> post != null)
                .map(post -> toResult(
                        post,
                        imagesByPostNo.getOrDefault(post.getPostNo(), List.of()),
                        likeDeltas.getOrDefault(post.getPostNo(), 0L)))
                .toList();
    }

    private PostResult toResult(PostEntity post, List<PostImageEntity> images, long likeDelta) {
        return new PostResult(
                post.getPostNo(),
                post.getAuthorId(),
                post.getContent(),
                post.getImageCount(),
                post.getStatus(),
                post.getLikeCount() + likeDelta,
                post.getCommentCount(),
                post.getPublishedAt(),
                images.stream().map(this::toImageResult).toList());
    }

    private PostImageResult toImageResult(PostImageEntity image) {
        return new PostImageResult(
                image.getImageNo(),
                image.getBucket(),
                image.getObjectKey(),
                image.getContentType(),
                image.getSizeBytes(),
                image.getWidth(),
                image.getHeight(),
                image.getSortOrder());
    }

    private Optional<UserGender> resolveCurrentGender(Long userId) {
        Optional<UserGender> cached = findCachedGender(userId);
        if (cached.isPresent()) {
            return cached;
        }
        try {
            Optional<UserGender> gender = feedUserClient.findGender(userId);
            gender.ifPresent(value -> cacheGender(userId, value));
            return gender;
        } catch (RuntimeException ex) {
            log.warn("查询当前用户性别失败，userId={}, errorType={}, errorMessage={}",
                    userId, ex.getClass().getSimpleName(), ex.getMessage());
            return Optional.empty();
        }
    }

    private Map<Long, UserGender> resolveAuthorGenders(Collection<Long> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, UserGender> genders = new HashMap<>();
        try {
            Map<Long, UserGender> remoteGenders = feedUserClient.findGenders(authorIds);
            if (remoteGenders != null) {
                genders.putAll(remoteGenders);
                remoteGenders.forEach(this::cacheGender);
            }
        } catch (RuntimeException ex) {
            log.warn("批量查询候选作者性别失败，authorCount={}, errorType={}, errorMessage={}",
                    authorIds.size(), ex.getClass().getSimpleName(), ex.getMessage());
        }
        for (Long authorId : authorIds) {
            if (!genders.containsKey(authorId)) {
                findCachedGender(authorId).ifPresent(gender -> genders.put(authorId, gender));
            }
        }
        return genders;
    }

    private Optional<UserGender> findCachedGender(Long userId) {
        try {
            return feedCache.findCachedGender(userId);
        } catch (RuntimeException ex) {
            log.warn("读取用户性别缓存失败，userId={}, errorType={}, errorMessage={}",
                    userId, ex.getClass().getSimpleName(), ex.getMessage());
            return Optional.empty();
        }
    }

    private void cacheGender(Long userId, UserGender gender) {
        try {
            feedCache.cacheGender(userId, gender);
        } catch (RuntimeException ex) {
            log.warn("写入用户性别缓存失败，userId={}, errorType={}, errorMessage={}",
                    userId, ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    private List<Long> listLikedUserIds(Long userId) {
        try {
            List<Long> likedUserIds = feedUserClient.listLikedUserIds(userId);
            return likedUserIds == null ? List.of() : likedUserIds;
        } catch (RuntimeException ex) {
            log.warn("查询喜欢过的人失败，userId={}, errorType={}, errorMessage={}",
                    userId, ex.getClass().getSimpleName(), ex.getMessage());
            return List.of();
        }
    }

    private Map<Long, Long> findLikedAuthorLatestPostNos(Long userId, List<Long> likedUserIds) {
        try {
            Map<Long, Long> postNosByAuthor = feedCache.findLikedAuthorLatestPostNos(likedUserIds);
            return postNosByAuthor == null ? Map.of() : postNosByAuthor;
        } catch (RuntimeException ex) {
            log.warn("读取喜欢过的人最新帖子缓存失败，userId={}, likedUserCount={}, errorType={}, errorMessage={}",
                    userId, likedUserIds.size(), ex.getClass().getSimpleName(), ex.getMessage());
            return Map.of();
        }
    }

    private Map<Long, Long> filterUnconsumedLikedLatestPosts(Long userId, Map<Long, Long> latestPostNosByAuthor) {
        try {
            Map<Long, Long> postNosByAuthor = feedCache.filterUnconsumedLikedLatestPosts(userId, latestPostNosByAuthor);
            return postNosByAuthor == null ? Map.of() : postNosByAuthor;
        } catch (RuntimeException ex) {
            log.warn("过滤喜欢过的人已消费最新帖失败，userId={}, candidateCount={}, errorType={}, errorMessage={}",
                    userId,
                    latestPostNosByAuthor == null ? 0 : latestPostNosByAuthor.size(),
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
            return latestPostNosByAuthor == null ? Map.of() : latestPostNosByAuthor;
        }
    }

    private void deleteInvalidLikedAuthorLatestPosts(Long userId, Collection<Long> authorIds) {
        if (authorIds == null || authorIds.isEmpty()) {
            return;
        }
        try {
            feedCache.deleteInvalidLikedAuthorLatestPosts(authorIds);
        } catch (RuntimeException ex) {
            log.warn("删除无效喜欢作者最新帖子缓存失败，userId={}, authorCount={}, errorType={}, errorMessage={}",
                    userId, authorIds.size(), ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    private Map<Long, Long> findLatestPostNosForHotBaseScore(Collection<Long> authorIds) {
        try {
            Map<Long, Long> postNosByAuthor = feedCache.findLikedAuthorLatestPostNos(authorIds);
            return postNosByAuthor == null ? Map.of() : postNosByAuthor;
        } catch (RuntimeException ex) {
            log.warn("读取热门基础分最新帖子缓存失败，authorCount={}, errorType={}, errorMessage={}",
                    authorIds == null ? 0 : authorIds.size(), ex.getClass().getSimpleName(), ex.getMessage());
            return Map.of();
        }
    }

    private OffsetDateTime resolveNewBefore(
            GetFeedCommand command,
            UserGender targetGender,
            Optional<FeedCursor> cursor,
            OffsetDateTime now) {
        if (command.refresh()) {
            return now;
        }
        if (cursor.isPresent()) {
            return cursor.orElseThrow().newBefore();
        }
        try {
            return feedCache.findNewCursor(command.userId(), targetGender).orElse(now);
        } catch (RuntimeException ex) {
            log.warn("读取新帖时间游标失败，userId={}, targetGender={}, errorType={}, errorMessage={}",
                    command.userId(), targetGender, ex.getClass().getSimpleName(), ex.getMessage());
            return now;
        }
    }

    private int resolveHotOffset(GetFeedCommand command, Optional<FeedCursor> cursor) {
        if (command.refresh() || cursor.isEmpty()) {
            return 0;
        }
        return Math.max(0, cursor.orElseThrow().hotOffset());
    }

    private Optional<FeedCursor> resolveCursor(GetFeedCommand command, UserGender targetGender, OffsetDateTime now) {
        if (command.refresh() || command.cursor() == null || command.cursor().isBlank()) {
            return Optional.empty();
        }
        FeedCursor cursor = feedCursorCodec.decode(command.cursor()).orElseThrow();
        if (!command.userId().equals(cursor.userId()) || cursor.targetGender() != targetGender) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, "Feed cursor 无效");
        }
        if (cursor.expireAt().isBefore(now)) {
            return Optional.empty();
        }
        return Optional.of(cursor);
    }

    private OffsetDateTime nextNewBefore(List<FeedCandidate> selectedCandidates, OffsetDateTime fallback) {
        List<Long> newPostNos = selectedCandidates.stream()
                .filter(candidate -> candidate.source() == FeedSource.NEW)
                .map(FeedCandidate::postNo)
                .toList();
        if (newPostNos.isEmpty()) {
            return fallback;
        }
        return feedManager.listPublishedByPostNos(newPostNos).stream()
                .map(PostEntity::getPublishedAt)
                .min(Comparator.naturalOrder())
                .orElse(fallback);
    }

    private void saveUserState(
            Long userId,
            UserGender targetGender,
            List<FeedCandidate> selectedCandidates,
            List<PostResult> results,
            OffsetDateTime nextNewBefore) {
        try {
            Set<Long> returnedPostNos = results.stream()
                    .map(PostResult::postNo)
                    .collect(Collectors.toSet());
            feedCache.markLikedConsumed(
                    userId,
                    selectedCandidates.stream()
                            .filter(candidate -> candidate.source() == FeedSource.LIKED)
                            .filter(candidate -> candidate.authorId() != null)
                            .filter(candidate -> returnedPostNos.contains(candidate.postNo()))
                            .collect(Collectors.toMap(
                                    FeedCandidate::authorId,
                                    FeedCandidate::postNo,
                                    (left, right) -> left,
                                    LinkedHashMap::new)));
            feedCache.saveNewCursor(userId, targetGender, nextNewBefore);
        } catch (RuntimeException ex) {
            log.warn("写入 Feed 短期状态失败，userId={}, targetGender={}, errorType={}, errorMessage={}",
                    userId, targetGender, ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    private void clearUserState(Long userId, UserGender targetGender) {
        try {
            feedCache.clearUserState(userId, targetGender);
        } catch (RuntimeException ex) {
            log.warn("清空 Feed 短期状态失败，userId={}, targetGender={}, errorType={}, errorMessage={}",
                    userId, targetGender, ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    private Map<Long, Long> readLikeDeltas(Collection<Long> postNos) {
        try {
            Map<Long, Long> deltas = likeCountCache.readLikeDeltas(postNos);
            return deltas == null ? Map.of() : deltas;
        } catch (RuntimeException ex) {
            log.warn("Feed 读取点赞 Redis delta 失败，postCount={}, errorType={}, errorMessage={}",
                    postNos == null ? 0 : postNos.size(), ex.getClass().getSimpleName(), ex.getMessage());
            return Map.of();
        }
    }

    private double hotScore(PostEntity post, long likeDelta, OffsetDateTime now, boolean hasBaseScore) {
        long likeCount = post.getLikeCount() + likeDelta;
        long commentCount = post.getCommentCount();
        double hoursSincePublished = Math.max(
                0.0D,
                Duration.between(post.getPublishedAt(), now).toMinutes() / 60.0D);
        double baseScore = hasBaseScore ? HOT_BASE_WEIGHT : 0.0D;
        return (baseScore + HOT_LIKE_WEIGHT * likeCount + HOT_COMMENT_WEIGHT * commentCount)
                / Math.pow(hoursSincePublished + 2.0D, 1.5D);
    }

    private FeedPageResult emptyPage() {
        return new FeedPageResult(List.of(), "", PAGE_SIZE, false);
    }

    private void validateCommand(GetFeedCommand command) {
        if (command == null) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, "Feed 查询命令不能为空");
        }
        if (command.userId() == null || command.userId() <= 0) {
            throw new BusinessException(PostErrorCode.INVALID_ARGUMENT, "userId 必须为正整数");
        }
    }
}
