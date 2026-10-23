package com.biecuoguo.post.internal;

import com.biecuoguo.service.PresenceService;
import com.biecuoguo.service.ExperienceService;

import com.biecuoguo.domain.User;

import com.biecuoguo.config.AppProperties;
import com.biecuoguo.config.AppTime;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.biecuoguo.domain.*;
import com.biecuoguo.admin.api.AdminAuditApi;
import com.biecuoguo.post.api.PostAdminApi;
import com.biecuoguo.post.api.PostDtos;
import com.biecuoguo.post.api.PostQueryApi;
import com.biecuoguo.mapper.*;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.social.api.BlockQueryApi;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PostService implements PostQueryApi, PostAdminApi {
    private static final Set<String> BOARDS = Set.of("recommend", "school", "college", "major");

    private final AppProperties appProperties;
    private final PostMapper postMapper;
    private final PostImageMapper imageMapper;
    private final PostLikeMapper likeMapper;
    private final PostFavoriteMapper favoriteMapper;
    private final PostShareMapper shareMapper;
    private final PostDailyStatMapper dailyStatMapper;
    private final CommentMapper commentMapper;
    private final CommentLikeMapper commentLikeMapper;
    private final UserMapper userMapper;
    private final SchoolMapper schoolMapper;
    private final CollegeMapper collegeMapper;
    private final MajorMapper majorMapper;
    private final PresenceService presenceService;
    private final ExperienceService experienceService;
    private final BlockQueryApi blockQueryApi;
    private final AdminAuditApi auditApi;

    public PostService(AppProperties appProperties, PostMapper postMapper, PostImageMapper imageMapper, PostLikeMapper likeMapper, PostFavoriteMapper favoriteMapper, PostShareMapper shareMapper, PostDailyStatMapper dailyStatMapper, CommentMapper commentMapper, CommentLikeMapper commentLikeMapper, UserMapper userMapper, SchoolMapper schoolMapper, CollegeMapper collegeMapper, MajorMapper majorMapper, PresenceService presenceService, ExperienceService experienceService, BlockQueryApi blockQueryApi, AdminAuditApi auditApi) {
        this.auditApi = auditApi;
        this.appProperties = appProperties;
        this.postMapper = postMapper;
        this.imageMapper = imageMapper;
        this.likeMapper = likeMapper;
        this.favoriteMapper = favoriteMapper;
        this.shareMapper = shareMapper;
        this.dailyStatMapper = dailyStatMapper;
        this.commentMapper = commentMapper;
        this.commentLikeMapper = commentLikeMapper;
        this.userMapper = userMapper;
        this.schoolMapper = schoolMapper;
        this.collegeMapper = collegeMapper;
        this.majorMapper = majorMapper;
        this.presenceService = presenceService;
        this.experienceService = experienceService;
        this.blockQueryApi = blockQueryApi;
    }

    public List<PostDtos.PostView> list(String tab, CurrentUser currentUser) {
        return list(tab, currentUser, null, null).items();
    }

    /**
     * 游标分页（keyset pagination）的列表。
     *
     * 排序键与老版完全一致：pinned > official > dontMiss > likeCount > commentCount > publishedAt > id。
     *
     * 为什么不能只用 id 当游标：排序是按热度/权重排的，id 小的帖子完全可能排在
     * id 大的前面（实测 legacy 顺序 2,3,1,4,5,15,14,... 就是这种情况）。
     * 只按 id < cursor 过滤会把排在 cursor 之前、但 id 更小的帖子重复吐出来。
     * 所以游标必须编码上一页最后一条的**完整排序键元组**，
     * 下一页用「字典序小于」精确接续，这也是标准 keyset pagination 的做法。
     *
     * limit 上限 50、默认 20，见 docs/MOBILE-API-PLAN.md 4.3。
     */
    public PostDtos.PostPageView list(String tab, CurrentUser currentUser, String cursor, Integer requestedLimit) {
        User viewer = currentUser == null ? null : userMapper.selectById(currentUser.id());
        int limit = normalizeLimit(requestedLimit);
        LambdaQueryWrapper<Post> wrapper = baseVisibleWrapper(viewer);
        applyTab(wrapper, normalizeBoard(tab), viewer);
        PostCursor cursorKey = PostCursor.parse(cursor);
        if (cursorKey != null) {
            applyCursor(wrapper, cursorKey);
        }
        wrapper.orderByDesc(Post::getPinned)
                .orderByDesc(Post::getOfficial)
                .orderByDesc(Post::getDontMiss)
                .orderByDesc(Post::getLikeCount)
                .orderByDesc(Post::getCommentCount)
                .orderByDesc(Post::getPublishedAt)
                .orderByDesc(Post::getId);
        // 多查一条用于判断 hasMore
        List<Post> page = postMapper.selectList(wrapper.last("limit " + (limit + 1)));
        boolean hasMore = page.size() > limit;
        List<Post> items = hasMore ? page.subList(0, limit) : page;
        String nextCursor = hasMore ? PostCursor.of(items.get(items.size() - 1)).encode() : null;
        return PostDtos.PostPageView.of(toViews(items, currentUser), nextCursor, limit);
    }

    /**
     * 把「上一页最后一条的排序键」翻译成 SQL 的字典序小于条件。
     *
     * 排序是 (pinned, official, dontMiss, likeCount, commentCount, publishedAt, id) 全部 DESC，
     * 所以「排在它后面」等价于：第一个不同的键比它小。
     * 展开成 OR 链：
     *   pinned < k.pinned
     *   OR (pinned = k.pinned AND official < k.official)
     *   OR (pinned = k.pinned AND official = k.official AND dontMiss < k.dontMiss)
     *   ... 以此类推到 id
     */
    private void applyCursor(LambdaQueryWrapper<Post> wrapper, PostCursor key) {
        // LambdaQueryWrapper 没有 and(Consumer)，改用 AbstractWrapper 的 nested 形式：
        // 手写一段 OR 链，MyBatis-Plus 会自动补上外层括号，
        // 生成的 SQL 形如 AND ((a) OR (b) OR ...)，不会和现有条件串味。
        wrapper.nested(w -> {
            w.lt(Post::getPinned, key.pinned());
            w.or().eq(Post::getPinned, key.pinned()).lt(Post::getOfficial, key.official());
            w.or().eq(Post::getPinned, key.pinned()).eq(Post::getOfficial, key.official()).lt(Post::getDontMiss, key.dontMiss());
            w.or().eq(Post::getPinned, key.pinned()).eq(Post::getOfficial, key.official()).eq(Post::getDontMiss, key.dontMiss())
                    .lt(Post::getLikeCount, key.likeCount());
            w.or().eq(Post::getPinned, key.pinned()).eq(Post::getOfficial, key.official()).eq(Post::getDontMiss, key.dontMiss())
                    .eq(Post::getLikeCount, key.likeCount()).lt(Post::getCommentCount, key.commentCount());
            w.or().eq(Post::getPinned, key.pinned()).eq(Post::getOfficial, key.official()).eq(Post::getDontMiss, key.dontMiss())
                    .eq(Post::getLikeCount, key.likeCount()).eq(Post::getCommentCount, key.commentCount())
                    .lt(Post::getPublishedAt, key.publishedAt());
            w.or().eq(Post::getPinned, key.pinned()).eq(Post::getOfficial, key.official()).eq(Post::getDontMiss, key.dontMiss())
                    .eq(Post::getLikeCount, key.likeCount()).eq(Post::getCommentCount, key.commentCount())
                    .eq(Post::getPublishedAt, key.publishedAt()).lt(Post::getId, key.id());
        });
    }

    private int normalizeLimit(Integer requestedLimit) {
        int value = requestedLimit == null ? 20 : requestedLimit;
        return Math.max(1, Math.min(50, value));
    }

    /** 排序键元组：与 list() 的 ORDER BY 严格一一对应，顺序不能改。 */
    private record PostCursor(boolean pinned, boolean official, boolean dontMiss, int likeCount, int commentCount, LocalDateTime publishedAt, long id) {

        static PostCursor of(Post post) {
            return new PostCursor(
                    Boolean.TRUE.equals(post.getPinned()),
                    Boolean.TRUE.equals(post.getOfficial()),
                    Boolean.TRUE.equals(post.getDontMiss()),
                    post.getLikeCount() == null ? 0 : post.getLikeCount(),
                    post.getCommentCount() == null ? 0 : post.getCommentCount(),
                    post.getPublishedAt() == null ? LocalDateTime.MIN : post.getPublishedAt(),
                    post.getId()
            );
        }

        /**
         * 游标编码：字段用 | 分隔，Boolean 存 1/0，时间存 ISO-8601。
         * 之所以不 Base64：游标会出现在 URL 和日志里，可读性好排查；
         * 且这些都是服务端自己产出的值，不承载用户输入，无需防篡改。
         */
        String encode() {
            return pinned + "|" + official + "|" + dontMiss + "|" + likeCount + "|" + commentCount + "|"
                    + (publishedAt == null ? "" : publishedAt.toString()) + "|" + id;
        }

        static PostCursor parse(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            String[] parts = cursor.trim().split("\\|", -1);
            if (parts.length != 7) {
                throw new IllegalArgumentException("分页游标无效");
            }
            try {
                LocalDateTime publishedAt = parts[5].isEmpty() ? LocalDateTime.MIN : LocalDateTime.parse(parts[5]);
                return new PostCursor(
                        Boolean.parseBoolean(parts[0]),
                        Boolean.parseBoolean(parts[1]),
                        Boolean.parseBoolean(parts[2]),
                        Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]),
                        publishedAt,
                        Long.parseLong(parts[6])
                );
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("分页游标无效");
            }
        }
    }

    public List<PostDtos.BoardView> boards(CurrentUser currentUser) {
        User viewer = currentUser == null ? null : userMapper.selectById(currentUser.id());
        return List.of(
                new PostDtos.BoardView("recommend", "推荐", "按热度、官方权重和发布时间混排", countForBoard("recommend", viewer)),
                new PostDtos.BoardView("school", "校 PO", "全校同学都能看到的校园动态", countForBoard("school", viewer)),
                new PostDtos.BoardView("college", "院 PO", "同学院范围内更贴近课程和活动", countForBoard("college", viewer)),
                new PostDtos.BoardView("major", "专业 PO", "专业相关经验、作业、竞赛和提醒", countForBoard("major", viewer))
        );
    }

    public PostDtos.PostView hotToday(CurrentUser currentUser) {
        LocalDate today = AppTime.nowLocal().toLocalDate();
        Set<Long> blockedAuthors = currentUser == null ? Set.of() : blockQueryApi.blockedUserIds(currentUser.id());
        List<PostDailyStat> stats = dailyStatMapper.selectList(new LambdaQueryWrapper<PostDailyStat>()
                        .eq(PostDailyStat::getStatDate, today)
                        .orderByDesc(PostDailyStat::getLikeCount)
                        .orderByDesc(PostDailyStat::getCommentCount));
        if (stats.isEmpty()) {
            return null;
        }
        List<Long> statPostIds = stats.stream().map(PostDailyStat::getPostId).filter(Objects::nonNull).toList();
        Map<Long, Post> statPosts = postMapper.selectList(new LambdaQueryWrapper<Post>()
                        .in(Post::getId, statPostIds)
                        .notIn(!blockedAuthors.isEmpty(), Post::getAuthorId, blockedAuthors)
                        .eq(Post::getStatus, "published"))
                .stream().collect(Collectors.toMap(Post::getId, Function.identity(), (a, b) -> a));
        if (statPosts.isEmpty()) {
            return null;
        }
        // 只在排名前的少量候选里挑，避免把整表都转成 view
        List<Post> candidates = stats.stream()
                .sorted(Comparator.comparingInt((PostDailyStat item) -> safe(item.getLikeCount()) + safe(item.getCommentCount())).reversed())
                .map(stat -> statPosts.get(stat.getPostId()))
                .filter(Objects::nonNull)
                .limit(10)
                .toList();
        return toViews(candidates, currentUser).stream()
                .max(Comparator.comparingInt(post -> safe(post.likeCount()) + safe(post.commentCount())))
                .orElse(null);
    }

    public List<PostDtos.PostView> dontMiss(CurrentUser currentUser) {
        User viewer = currentUser == null ? null : userMapper.selectById(currentUser.id());
        LambdaQueryWrapper<Post> wrapper = baseVisibleWrapper(viewer)
                .eq(Post::getDontMiss, true)
                .orderByDesc(Post::getPinned)
                .orderByAsc(Post::getDeadlineAt);
        return toViews(postMapper.selectList(wrapper), currentUser);
    }

    @Transactional
    public PostDtos.PostView create(PostDtos.CreatePostRequest request, CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        User author = requireRewardUser(currentUser.id());
        validateBoard(request.board());
        if (author.getSchoolId() == null) {
            throw new IllegalArgumentException("请先在个人中心选择学校");
        }
        LocalDateTime now = AppTime.nowLocal();
        Post post = new Post();
        post.setAuthorId(author.getId());
        post.setSchoolId(author.getSchoolId());
        post.setCollegeId(author.getCollegeId());
        post.setMajorId(author.getMajorId());
        post.setBoard(normalizeBoard(request.board()));
        post.setSourceType(Boolean.TRUE.equals(request.official()) ? "official" : "student");
        post.setTitle(request.title().trim());
        post.setContent(request.content().trim());
        post.setExcerpt(excerpt(request.content()));
        post.setCoverUrl(firstNonBlank(request.coverUrl(), null));
        post.setRiskLevel(firstNonBlank(request.riskLevel(), "normal"));
        post.setOfficial(canPostOfficial(author, request.board()) && Boolean.TRUE.equals(request.official()));
        post.setDontMiss(Boolean.TRUE.equals(request.dontMiss()) && canPostOfficial(author, request.board()));
        post.setPinned(Boolean.TRUE.equals(request.pinned()) && "ADMIN".equals(author.getRole()));
        post.setDeadlineAt(AppTime.toLocal(request.deadlineAt()));
        post.setMissConsequence(request.missConsequence());
        post.setNextAction(request.nextAction());
        post.setTags(joinTags(request.tags()));
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setFavoriteCount(0);
        post.setShareCount(0);
        post.setStatus("published");
        post.setPublishedAt(now);
        post.setCreatedAt(now);
        post.setUpdatedAt(now);
        postMapper.insert(post);
        saveImages(post.getId(), request.images(), now);
        grantXp(author, 12);
        return toView(postMapper.selectById(post.getId()), currentUser);
    }

    @Transactional
    public boolean deleteOwnPost(Long postId, CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        Post post = requireMutablePost(postId, currentUser);
        if (!Objects.equals(post.getAuthorId(), currentUser.id())) {
            throw new AccessDeniedException("只能删除自己的 PO");
        }
        postMapper.update(null, new LambdaUpdateWrapper<Post>().eq(Post::getId, postId)
                .set(Post::getStatus, "deleted").set(Post::getUpdatedAt, AppTime.nowLocal()));
        return true;
    }

    public List<PostDtos.CommentView> comments(Long postId, CurrentUser currentUser) {
        Post post = requirePost(postId);
        requireNotBlocked(post, currentUser);
        List<Comment> comments = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getPostId, post.getId())
                .ne(Comment::getStatus, "hidden")
                .orderByDesc(Comment::getFeatured)
                .orderByAsc(Comment::getCreatedAt));
        return commentViews(comments, currentUser);
    }

    @Transactional
    public PostDtos.CommentView comment(Long postId, PostDtos.CreateCommentRequest request, CurrentUser currentUser) {
        return comment(postId, request, currentUser, null);
    }

    @Transactional
    public PostDtos.CommentView comment(Long postId, PostDtos.CreateCommentRequest request, CurrentUser currentUser, String requestKey) {
        requireAuthenticated(currentUser);
        String key = normalizeRequestKey(requestKey);
        Post post = requireMutablePost(postId, currentUser);
        if (request.parentId() != null) {
            Comment parent = commentMapper.lockById(request.parentId());
            if (parent == null || !Objects.equals(parent.getPostId(), postId) || "hidden".equals(parent.getStatus())) {
                throw new IllegalArgumentException("回复的评论不存在或不属于此帖子");
            }
            if (blockQueryApi.isBlockedEitherWay(currentUser.id(), parent.getUserId())) {
                throw new NoSuchElementException("回复的评论不存在");
            }
        }
        User author = requireRewardUser(currentUser.id());
        LocalDateTime now = AppTime.nowLocal();
        Comment comment = new Comment();
        comment.setPostId(post.getId());
        comment.setUserId(author.getId());
        comment.setParentId(request.parentId());
        comment.setRequestKey(key);
        comment.setContent(request.content().trim());
        comment.setLikeCount(0);
        comment.setReplyCount(0);
        comment.setFeatured(false);
        comment.setStatus("visible");
        comment.setCreatedAt(now);
        comment.setUpdatedAt(now);
        try { commentMapper.insert(comment); }
        catch (DuplicateKeyException duplicate) {
            if (key == null) { throw duplicate; }
            Comment existing = commentMapper.selectOne(new LambdaQueryWrapper<Comment>()
                    .eq(Comment::getUserId, author.getId()).eq(Comment::getRequestKey, key).last("FOR UPDATE"));
            if (existing == null) { throw duplicate; }
            if (!Objects.equals(existing.getPostId(), postId) || !Objects.equals(existing.getParentId(), request.parentId())
                    || !Objects.equals(existing.getContent(), request.content().trim())) {
                throw new IllegalArgumentException("此发送标识已用于另一条评论，请重新编辑后发送");
            }
            return commentViews(List.of(existing), currentUser).get(0);
        }
        postMapper.adjustCounters(postId, 0, 1, 0, 0, now);
        if (request.parentId() != null) { commentMapper.incrementReplies(request.parentId(), now); }
        upsertDailyStat(post.getId(), 0, 1, 0);
        grantXp(author, 4);
        return commentViews(List.of(comment), currentUser).get(0);
    }

    @Transactional
    public boolean likeComment(Long commentId, CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        Comment comment = requireMutableComment(commentId, currentUser);
        CommentLike like = new CommentLike();
        like.setCommentId(commentId);
        like.setUserId(currentUser.id());
        like.setCreatedAt(AppTime.nowLocal());
        try { commentLikeMapper.insert(like); }
        catch (DuplicateKeyException duplicate) {
            if (commentLikeMapper.selectOne(new LambdaQueryWrapper<CommentLike>()
                    .eq(CommentLike::getCommentId, commentId).eq(CommentLike::getUserId, currentUser.id())
                    .last("FOR UPDATE")) == null) { throw duplicate; }
            return true;
        }
        commentMapper.adjustLikes(comment.getId(), 1, AppTime.nowLocal());
        return true;
    }

    @Transactional
    public boolean unlikeComment(Long commentId, CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        Comment comment = requireMutableComment(commentId, currentUser);
        int deleted = commentLikeMapper.delete(new LambdaQueryWrapper<CommentLike>()
                .eq(CommentLike::getCommentId, commentId)
                .eq(CommentLike::getUserId, currentUser.id()));
        if (deleted > 0) {
            commentMapper.adjustLikes(comment.getId(), -deleted, AppTime.nowLocal());
        }
        return true;
    }

    @Transactional
    public boolean like(Long postId, CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        Post post = requireMutablePost(postId, currentUser);
        Long userId = currentUser.id();
        PostLike like = new PostLike();
        like.setPostId(postId);
        like.setUserId(userId);
        like.setCreatedAt(AppTime.nowLocal());
        try { likeMapper.insert(like); }
        catch (DuplicateKeyException duplicate) {
            if (likeMapper.selectOne(new LambdaQueryWrapper<PostLike>().eq(PostLike::getPostId, postId)
                    .eq(PostLike::getUserId, userId).last("FOR UPDATE")) == null) { throw duplicate; }
            return true;
        }
        postMapper.adjustCounters(postId, 1, 0, 0, 0, AppTime.nowLocal());
        upsertDailyStat(postId, 1, 0, 0);
        return true;
    }

    @Transactional
    public boolean unlike(Long postId, CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        requireMutablePost(postId, currentUser);
        int deleted = likeMapper.delete(new LambdaQueryWrapper<PostLike>().eq(PostLike::getPostId, postId).eq(PostLike::getUserId, currentUser.id()));
        if (deleted > 0) {
            postMapper.adjustCounters(postId, -deleted, 0, 0, 0, AppTime.nowLocal());
        }
        return true;
    }

    @Transactional
    public boolean favorite(Long postId, CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        requireMutablePost(postId, currentUser);
        PostFavorite favorite = new PostFavorite();
        favorite.setPostId(postId);
        favorite.setUserId(currentUser.id());
        favorite.setCreatedAt(AppTime.nowLocal());
        try { favoriteMapper.insert(favorite); }
        catch (DuplicateKeyException duplicate) {
            if (favoriteMapper.selectOne(new LambdaQueryWrapper<PostFavorite>().eq(PostFavorite::getPostId, postId)
                    .eq(PostFavorite::getUserId, currentUser.id()).last("FOR UPDATE")) == null) { throw duplicate; }
            return true;
        }
        postMapper.adjustCounters(postId, 0, 0, 1, 0, AppTime.nowLocal());
        return true;
    }

    @Transactional
    public boolean unfavorite(Long postId, CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        requireMutablePost(postId, currentUser);
        int deleted = favoriteMapper.delete(new LambdaQueryWrapper<PostFavorite>().eq(PostFavorite::getPostId, postId).eq(PostFavorite::getUserId, currentUser.id()));
        if (deleted > 0) {
            postMapper.adjustCounters(postId, 0, 0, -deleted, 0, AppTime.nowLocal());
        }
        return true;
    }

    @Transactional
    public PostDtos.ShareResponse share(Long postId, CurrentUser currentUser) {
        return share(postId, currentUser, null);
    }

    @Transactional
    public PostDtos.ShareResponse share(Long postId, CurrentUser currentUser, String requestKey) {
        requireAuthenticated(currentUser);
        String key = normalizeRequestKey(requestKey);
        Post post = requireMutablePost(postId, currentUser);
        PostShare share = new PostShare();
        share.setPostId(postId);
        share.setUserId(currentUser.id());
        share.setChannel("copy_link");
        share.setRequestKey(key);
        share.setResultShareCount(safe(post.getShareCount()) + 1);
        share.setCreatedAt(AppTime.nowLocal());
        try { shareMapper.insert(share); }
        catch (DuplicateKeyException duplicate) {
            if (key == null) { throw duplicate; }
            PostShare existing = shareMapper.selectOne(new LambdaQueryWrapper<PostShare>()
                    .eq(PostShare::getUserId, currentUser.id()).eq(PostShare::getRequestKey, key).last("FOR UPDATE"));
            if (existing == null) { throw duplicate; }
            if (!Objects.equals(existing.getPostId(), postId)) {
                throw new IllegalArgumentException("此分享标识已用于另一篇帖子");
            }
            return new PostDtos.ShareResponse("/posts/" + postId, existing.getResultShareCount());
        }
        postMapper.adjustCounters(postId, 0, 0, 0, 1, AppTime.nowLocal());
        upsertDailyStat(postId, 0, 0, 1);
        return new PostDtos.ShareResponse("/posts/" + postId, share.getResultShareCount());
    }

    @Override
    public List<PostDtos.PostView> search(String keyword, String status, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<Post>()
                .eq(status != null && !status.isBlank(), Post::getStatus, status)
                .orderByDesc(Post::getId)
                .last("LIMIT " + safeLimit);
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim() + "%";
            wrapper.and(w -> w.like(Post::getTitle, like).or().like(Post::getContent, like));
        }
        return toViews(postMapper.selectList(wrapper), null);
    }

    @Override
    @Transactional
    public PostDtos.PostView moderate(long postId, Boolean pinned, Boolean official, Boolean dontMiss, String status) {
        Post post = postMapper.lockById(postId);
        if (post == null) {
            throw new NoSuchElementException("帖子不存在");
        }
        if (status != null) {
            if (!Set.of("published", "removed", "draft").contains(status)) {
                throw new IllegalArgumentException("status 只能是 published / removed / draft");
            }
            post.setStatus(status);
        }
        if (pinned != null) post.setPinned(pinned);
        if (official != null) post.setOfficial(official);
        if (dontMiss != null) post.setDontMiss(dontMiss);
        post.setUpdatedAt(AppTime.nowLocal());
        LambdaUpdateWrapper<Post> update = new LambdaUpdateWrapper<Post>().eq(Post::getId, postId)
                .set(Post::getUpdatedAt, AppTime.nowLocal());
        if (status != null) update.set(Post::getStatus, status);
        if (pinned != null) update.set(Post::getPinned, pinned);
        if (official != null) update.set(Post::getOfficial, official);
        if (dontMiss != null) update.set(Post::getDontMiss, dontMiss);
        postMapper.update(null, update);
        return toViews(List.of(post), null).get(0);
    }

    @Override
    public UserPostStats statsForUser(long userId) {
        long comments = commentMapper.selectCount(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getUserId, userId)
                .isNotNull(Comment::getPostId));
        long likes = likeMapper.selectCount(new LambdaQueryWrapper<PostLike>().eq(PostLike::getUserId, userId));
        long favorites = favoriteMapper.selectCount(new LambdaQueryWrapper<PostFavorite>().eq(PostFavorite::getUserId, userId));
        long shares = shareMapper.selectCount(new LambdaQueryWrapper<PostShare>().eq(PostShare::getUserId, userId));
        long posts = postMapper.selectCount(new LambdaQueryWrapper<Post>()
                .eq(Post::getAuthorId, userId).eq(Post::getStatus, "published"));
        return new UserPostStats(posts, comments, likes, favorites, shares);
    }

    @Override
    public AdminPostStats adminStats() {
        long posts = postMapper.selectCount(null);
        long published = postMapper.selectCount(new LambdaQueryWrapper<Post>().eq(Post::getStatus, "published"));
        long comments = commentMapper.selectCount(new LambdaQueryWrapper<Comment>().isNotNull(Comment::getPostId));
        return new AdminPostStats(posts, published, comments);
    }

    public List<PostDtos.PostView> userPosts(Long userId, CurrentUser currentUser) {
        if (isBlockedPair(currentUser, userId)) return List.of();
        return toViews(postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getAuthorId, userId)
                .eq(Post::getStatus, "published")
                .orderByDesc(Post::getPublishedAt)), currentUser);
    }

    public List<PostDtos.PostView> userPostsByUid(String uid, CurrentUser currentUser) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPublicUid, uid));
        if (user == null) {
            throw new NoSuchElementException("用户不存在");
        }
        if (isBlockedPair(currentUser, user.getId())) return List.of();
        return userPosts(user.getId(), currentUser);
    }

    public List<PostDtos.PostView> likedPosts(CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        List<Long> ids = likeMapper.selectList(new LambdaQueryWrapper<PostLike>().eq(PostLike::getUserId, currentUser.id()))
                .stream().map(PostLike::getPostId).toList();
        return postsByIds(ids, currentUser);
    }

    public List<PostDtos.PostView> likedPostsByUid(String uid, CurrentUser currentUser) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPublicUid, uid));
        if (user == null) {
            throw new NoSuchElementException("用户不存在");
        }
        if (isBlockedPair(currentUser, user.getId())) return List.of();
        List<Long> ids = likeMapper.selectList(new LambdaQueryWrapper<PostLike>().eq(PostLike::getUserId, user.getId()))
                .stream().map(PostLike::getPostId).toList();
        return postsByIds(ids, currentUser);
    }

    public List<PostDtos.PostView> favoritePosts(CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        List<Long> ids = favoriteMapper.selectList(new LambdaQueryWrapper<PostFavorite>().eq(PostFavorite::getUserId, currentUser.id()))
                .stream().map(PostFavorite::getPostId).toList();
        return postsByIds(ids, currentUser);
    }

    public List<PostDtos.PostView> favoritePostsByUid(String uid, CurrentUser currentUser) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPublicUid, uid));
        if (user == null) {
            throw new NoSuchElementException("用户不存在");
        }
        if (isBlockedPair(currentUser, user.getId())) return List.of();
        List<Long> ids = favoriteMapper.selectList(new LambdaQueryWrapper<PostFavorite>().eq(PostFavorite::getUserId, user.getId()))
                .stream().map(PostFavorite::getPostId).toList();
        return postsByIds(ids, currentUser);
    }

    public List<PostDtos.InteractionNoticeView> interactionNotices(CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        Long currentUserId = currentUser.id();
        Set<Long> blockedAuthors = blockQueryApi.blockedUserIds(currentUserId);
        List<Post> myPosts = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getAuthorId, currentUserId)
                .eq(Post::getStatus, "published"));
        Map<Long, Post> postMap = myPosts.stream().collect(Collectors.toMap(Post::getId, Function.identity()));
        List<Long> postIds = new ArrayList<>(postMap.keySet());

        List<PostLike> likes = postIds.isEmpty() ? List.of() : likeMapper.selectList(new LambdaQueryWrapper<PostLike>()
                .in(PostLike::getPostId, postIds)
                .notIn(!blockedAuthors.isEmpty(), PostLike::getUserId, blockedAuthors)
                .ne(PostLike::getUserId, currentUserId)
                .orderByDesc(PostLike::getCreatedAt));
        List<PostFavorite> favorites = postIds.isEmpty() ? List.of() : favoriteMapper.selectList(new LambdaQueryWrapper<PostFavorite>()
                .in(PostFavorite::getPostId, postIds)
                .notIn(!blockedAuthors.isEmpty(), PostFavorite::getUserId, blockedAuthors)
                .ne(PostFavorite::getUserId, currentUserId)
                .orderByDesc(PostFavorite::getCreatedAt));
        List<Comment> comments = postIds.isEmpty() ? List.of() : commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .in(Comment::getPostId, postIds)
                .notIn(!blockedAuthors.isEmpty(), Comment::getUserId, blockedAuthors)
                .ne(Comment::getUserId, currentUserId)
                .ne(Comment::getStatus, "hidden")
                .orderByDesc(Comment::getCreatedAt));

        List<Comment> myRootComments = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getUserId, currentUserId)
                .isNotNull(Comment::getPostId)
                .isNull(Comment::getParentId)
                .ne(Comment::getStatus, "hidden"));
        List<Long> myRootCommentIds = myRootComments.stream().map(Comment::getId).toList();
        List<Comment> commentReplies = myRootCommentIds.isEmpty() ? List.of() : commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .in(Comment::getParentId, myRootCommentIds)
                .notIn(!blockedAuthors.isEmpty(), Comment::getUserId, blockedAuthors)
                .ne(Comment::getUserId, currentUserId)
                .ne(Comment::getStatus, "hidden")
                .orderByDesc(Comment::getCreatedAt));
        Set<Long> replyCommentIds = commentReplies.stream().map(Comment::getId).collect(Collectors.toSet());
        comments = comments.stream()
                .filter(comment -> !replyCommentIds.contains(comment.getId()))
                .toList();

        Set<Long> replyPostIds = commentReplies.stream()
                .map(Comment::getPostId)
                .filter(Objects::nonNull)
                .filter(postId -> !postMap.containsKey(postId))
                .collect(Collectors.toSet());
        if (!replyPostIds.isEmpty()) {
            postMapper.selectList(new LambdaQueryWrapper<Post>()
                            .in(Post::getId, replyPostIds)
                            .notIn(!blockedAuthors.isEmpty(), Post::getAuthorId, blockedAuthors)
                            .eq(Post::getStatus, "published"))
                    .forEach(post -> postMap.put(post.getId(), post));
        }

        Set<Long> actorIds = new HashSet<>();
        likes.forEach(item -> actorIds.add(item.getUserId()));
        favorites.forEach(item -> actorIds.add(item.getUserId()));
        comments.forEach(item -> actorIds.add(item.getUserId()));
        commentReplies.forEach(item -> actorIds.add(item.getUserId()));
        Map<Long, User> users = actorIds.isEmpty() ? Map.of() : userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getId, actorIds))
                .stream().collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));

        // 涉及到的帖子一次性批量转 view，避免每条 notice 各走一遍 N+1
        Map<Long, PostDtos.PostView> postViews = toViews(postMap.values().stream().toList(), currentUser).stream()
                .collect(Collectors.toMap(PostDtos.PostView::id, Function.identity(), (a, b) -> a));
        // actor 的字典表也统一批量补齐
        Set<Long> actorSchoolIds = new HashSet<>();
        Set<Long> actorCollegeIds = new HashSet<>();
        Set<Long> actorMajorIds = new HashSet<>();
        users.values().forEach(actor -> {
            if (actor.getSchoolId() != null) {
                actorSchoolIds.add(actor.getSchoolId());
            }
            if (actor.getCollegeId() != null) {
                actorCollegeIds.add(actor.getCollegeId());
            }
            if (actor.getMajorId() != null) {
                actorMajorIds.add(actor.getMajorId());
            }
        });
        Map<Long, School> actorSchools = actorSchoolIds.isEmpty() ? Map.of() : schoolMapper.selectList(new LambdaQueryWrapper<School>().in(School::getId, actorSchoolIds))
                .stream().collect(Collectors.toMap(School::getId, Function.identity(), (a, b) -> a));
        Map<Long, College> actorColleges = actorCollegeIds.isEmpty() ? Map.of() : collegeMapper.selectList(new LambdaQueryWrapper<College>().in(College::getId, actorCollegeIds))
                .stream().collect(Collectors.toMap(College::getId, Function.identity(), (a, b) -> a));
        Map<Long, Major> actorMajors = actorMajorIds.isEmpty() ? Map.of() : majorMapper.selectList(new LambdaQueryWrapper<Major>().in(Major::getId, actorMajorIds))
                .stream().collect(Collectors.toMap(Major::getId, Function.identity(), (a, b) -> a));

        List<PostDtos.InteractionNoticeView> notices = new ArrayList<>();
        likes.forEach(item -> {
            User actor = users.get(item.getUserId());
            PostDtos.PostView postView = postViews.get(item.getPostId());
            if (actor != null && postView != null) {
                notices.add(new PostDtos.InteractionNoticeView(
                        item.getId(), "like", authorView(actor, actorSchools, actorColleges, actorMajors), postView, null, null, AppTime.toOffset(item.getCreatedAt()), true
                ));
            }
        });
        favorites.forEach(item -> {
            User actor = users.get(item.getUserId());
            PostDtos.PostView postView = postViews.get(item.getPostId());
            if (actor != null && postView != null) {
                notices.add(new PostDtos.InteractionNoticeView(
                        item.getId(), "favorite", authorView(actor, actorSchools, actorColleges, actorMajors), postView, null, null, AppTime.toOffset(item.getCreatedAt()), true
                ));
            }
        });
        comments.forEach(item -> {
            User actor = users.get(item.getUserId());
            PostDtos.PostView postView = postViews.get(item.getPostId());
            if (actor != null && postView != null) {
                notices.add(new PostDtos.InteractionNoticeView(
                        item.getId(), "comment", authorView(actor, actorSchools, actorColleges, actorMajors), postView, item.getContent(), item.getParentId(), AppTime.toOffset(item.getCreatedAt()), true
                ));
            }
        });
        commentReplies.forEach(item -> {
            User actor = users.get(item.getUserId());
            PostDtos.PostView postView = postViews.get(item.getPostId());
            if (actor != null && postView != null) {
                notices.add(new PostDtos.InteractionNoticeView(
                        item.getId(), "reply", authorView(actor, actorSchools, actorColleges, actorMajors), postView, item.getContent(), item.getParentId(), AppTime.toOffset(item.getCreatedAt()), true
                ));
            }
        });
        return notices.stream()
                .sorted(Comparator.comparing(PostDtos.InteractionNoticeView::createdAt).reversed())
                .limit(50)
                .toList();
    }

    private List<PostDtos.PostView> postsByIds(List<Long> ids, CurrentUser currentUser) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Set<Long> blockedAuthors = currentUser == null ? Set.of() : blockQueryApi.blockedUserIds(currentUser.id());
        return toViews(postMapper.selectList(new LambdaQueryWrapper<Post>()
                .in(Post::getId, ids)
                .eq(Post::getStatus, "published")
                .notIn(!blockedAuthors.isEmpty(), Post::getAuthorId, blockedAuthors)
                .orderByDesc(Post::getPublishedAt)), currentUser);
    }

    private LambdaQueryWrapper<Post> baseVisibleWrapper(User viewer) {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<Post>().eq(Post::getStatus, "published");
        // 学校隔离：登录用户只看自己学校；未登录（或资料里没有学校）落在配置的默认学校上。
        // 这里以前有两个坑：访客分支借的是 defaultViewer() 随便挑的用户的 school_id，
        // 决定看到什么帖子的是「MySQL 先返回哪一行」；再用一个 1L 兜底，等于把魔数写在 SQL 里。
        // 现在只有一个来源：app.default-school-id。
        Long schoolId = viewer == null || viewer.getSchoolId() == null ? defaultSchoolId() : viewer.getSchoolId();
        Set<Long> blockedAuthors = viewer == null ? Set.of() : blockQueryApi.blockedUserIds(viewer.getId());
        return wrapper.eq(Post::getSchoolId, schoolId)
                .notIn(!blockedAuthors.isEmpty(), Post::getAuthorId, blockedAuthors);
    }

    private boolean isBlockedPair(CurrentUser viewer, Long authorId) {
        return viewer != null && authorId != null
                && blockQueryApi.isBlockedEitherWay(viewer.id(), authorId);
    }

    private void requireNotBlocked(Post post, CurrentUser viewer) {
        if (post != null && isBlockedPair(viewer, post.getAuthorId())) {
            throw new NoSuchElementException("PO 不存在");
        }
    }

    /** 未登录访客 / 没填学校的用户看到的默认学校，来自 {@code app.default-school-id}。 */
    private Long defaultSchoolId() {
        Long configured = appProperties.defaultSchoolId();
        return configured == null ? 1L : configured;
    }

    private void applyTab(LambdaQueryWrapper<Post> wrapper, String tab, User viewer) {
        if ("school".equals(tab)) {
            wrapper.eq(Post::getBoard, "school");
        } else if ("college".equals(tab)) {
            wrapper.eq(Post::getBoard, "college");
            if (viewer != null && viewer.getCollegeId() != null) {
                wrapper.eq(Post::getCollegeId, viewer.getCollegeId());
            }
        } else if ("major".equals(tab)) {
            wrapper.eq(Post::getBoard, "major");
            if (viewer != null && viewer.getMajorId() != null) {
                wrapper.eq(Post::getMajorId, viewer.getMajorId());
            }
        }
    }

    private Long countForBoard(String board, User viewer) {
        LambdaQueryWrapper<Post> wrapper = baseVisibleWrapper(viewer);
        applyTab(wrapper, board, viewer);
        return postMapper.selectCount(wrapper);
    }

    private List<PostDtos.PostView> toViews(List<Post> posts, CurrentUser currentUser) {
        if (posts.isEmpty()) {
            return List.of();
        }
        List<Long> postIds = posts.stream().map(Post::getId).toList();

        // 1) 作者一次批量查完
        Set<Long> authorIds = posts.stream().map(Post::getAuthorId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, User> authors = authorIds.isEmpty() ? Map.of() : userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getId, authorIds))
                .stream().collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));

        // 2) 图片一次批量查完，按 sortOrder 保序
        Map<Long, List<String>> imagesByPost = imageMapper.selectList(new LambdaQueryWrapper<PostImage>()
                        .in(PostImage::getPostId, postIds)
                        .orderByAsc(PostImage::getSortOrder))
                .stream()
                .collect(Collectors.groupingBy(PostImage::getPostId,
                        Collectors.mapping(PostImage::getUrl, Collectors.toList())));

        // 3) 当前用户的点赞/收藏只查一次，用 Set 判断（final，供 lambda 捕获）
        final Set<Long> likedPostIds;
        final Set<Long> favoritedPostIds;
        if (currentUser != null) {
            likedPostIds = likeMapper.selectList(new LambdaQueryWrapper<PostLike>()
                            .eq(PostLike::getUserId, currentUser.id())
                            .in(PostLike::getPostId, postIds))
                    .stream().map(PostLike::getPostId).collect(Collectors.toSet());
            favoritedPostIds = favoriteMapper.selectList(new LambdaQueryWrapper<PostFavorite>()
                            .eq(PostFavorite::getUserId, currentUser.id())
                            .in(PostFavorite::getPostId, postIds))
                    .stream().map(PostFavorite::getPostId).collect(Collectors.toSet());
        } else {
            likedPostIds = Set.of();
            favoritedPostIds = Set.of();
        }

        // 4) authorView 的三张字典表按需批量补齐
        Set<Long> schoolIds = new HashSet<>();
        Set<Long> collegeIds = new HashSet<>();
        Set<Long> majorIds = new HashSet<>();
        authors.values().forEach(user -> {
            if (user.getSchoolId() != null) {
                schoolIds.add(user.getSchoolId());
            }
            if (user.getCollegeId() != null) {
                collegeIds.add(user.getCollegeId());
            }
            if (user.getMajorId() != null) {
                majorIds.add(user.getMajorId());
            }
        });
        Map<Long, School> schools = schoolIds.isEmpty() ? Map.of() : schoolMapper.selectList(new LambdaQueryWrapper<School>().in(School::getId, schoolIds))
                .stream().collect(Collectors.toMap(School::getId, Function.identity(), (a, b) -> a));
        Map<Long, College> colleges = collegeIds.isEmpty() ? Map.of() : collegeMapper.selectList(new LambdaQueryWrapper<College>().in(College::getId, collegeIds))
                .stream().collect(Collectors.toMap(College::getId, Function.identity(), (a, b) -> a));
        Map<Long, Major> majors = majorIds.isEmpty() ? Map.of() : majorMapper.selectList(new LambdaQueryWrapper<Major>().in(Major::getId, majorIds))
                .stream().collect(Collectors.toMap(Major::getId, Function.identity(), (a, b) -> a));

        return posts.stream()
                .map(post -> new PostDtos.PostView(
                        post.getId(), post.getBoard(), post.getSourceType(), post.getTitle(), post.getContent(), post.getExcerpt(),
                        post.getCoverUrl(), imagesByPost.getOrDefault(post.getId(), List.of()), post.getRiskLevel(), post.getOfficial(), post.getDontMiss(), post.getPinned(),
                        AppTime.toOffset(post.getDeadlineAt()), post.getMissConsequence(), post.getNextAction(), splitTags(post.getTags()),
                        safe(post.getLikeCount()), safe(post.getCommentCount()), safe(post.getFavoriteCount()), safe(post.getShareCount()),
                        likedPostIds.contains(post.getId()), favoritedPostIds.contains(post.getId()), post.getStatus(), AppTime.toOffset(post.getPublishedAt()), AppTime.toOffset(post.getCreatedAt()),
                        authorView(authors.get(post.getAuthorId()), schools, colleges, majors),
                        post.getVerifiedAt() == null ? null : post.getVerifiedAt().toString(),
                        AppTime.toOffset(post.getExpiresAt()),
                        isExpired(post, AppTime.nowLocal())
                ))
                .toList();
    }

    static boolean isExpired(Post post, LocalDateTime now) {
        return post.getExpiresAt() != null && !post.getExpiresAt().isAfter(now);
    }

    @Override
    public GuideSelection guidePosts(List<Long> postIds, CurrentUser currentUser) {
        if (postIds.isEmpty()) return new GuideSelection(List.of(), 0);
        User viewer = currentUser == null ? null : userMapper.selectById(currentUser.id());
        // 与信息流同一套可见性：同校、已发布、未拉黑；下架或别校的帖子直接不出现。
        List<Post> visible = postMapper.selectList(baseVisibleWrapper(viewer).in(Post::getId, postIds));
        LocalDateTime now = AppTime.nowLocal();
        Map<Long, Post> byId = visible.stream().collect(Collectors.toMap(Post::getId, Function.identity()));
        List<Post> valid = postIds.stream().map(byId::get).filter(Objects::nonNull)
                .filter(post -> !isExpired(post, now)).toList();
        return new GuideSelection(toViews(valid, currentUser), visible.size() - valid.size());
    }

    @Override
    public List<PostDtos.PostView> byIds(List<Long> postIds) {
        if (postIds.isEmpty()) return List.of();
        Map<Long, Post> byId = postMapper.selectBatchIds(postIds).stream()
                .collect(Collectors.toMap(Post::getId, Function.identity()));
        return toViews(postIds.stream().map(byId::get).filter(Objects::nonNull).toList(), null);
    }

    @Override
    @Transactional
    public PostDtos.PostView setValidity(long postId, LocalDate verifiedAt, OffsetDateTime expiresAt, CurrentUser actor) {
        Post post = postMapper.lockById(postId);
        if (post == null) {
            throw new NoSuchElementException("帖子不存在");
        }
        LocalDateTime expires = expiresAt == null ? null : AppTime.toLocal(expiresAt);
        postMapper.update(null, new LambdaUpdateWrapper<Post>().eq(Post::getId, postId)
                .set(Post::getVerifiedAt, verifiedAt)
                .set(Post::getExpiresAt, expires)
                .set(Post::getUpdatedAt, AppTime.nowLocal()));
        post.setVerifiedAt(verifiedAt);
        post.setExpiresAt(expires);
        auditApi.recordRequired(actor, "POST_VALIDITY", "post", postId,
                "verifiedAt=" + verifiedAt + " expiresAt=" + expires);
        return toViews(List.of(post), null).get(0);
    }

    private PostDtos.PostView toView(Post post, CurrentUser currentUser) {
        return toViews(List.of(post), currentUser).stream().findFirst().orElse(null);
    }

    private List<PostDtos.CommentView> commentViews(List<Comment> comments, CurrentUser currentUser) {
        if (comments.isEmpty()) {
            return List.of();
        }
        if (currentUser != null) {
            Set<Long> blockedAuthors = blockQueryApi.blockedUserIds(currentUser.id());
            if (!blockedAuthors.isEmpty()) {
                comments = comments.stream().filter(comment -> !blockedAuthors.contains(comment.getUserId())).toList();
                if (comments.isEmpty()) { return List.of(); }
            }
        }
        Set<Long> userIds = comments.stream().map(Comment::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, User> users = userIds.isEmpty() ? Map.of() : userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getId, userIds))
                .stream().collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
        Set<Long> likedCommentIds = currentUser == null ? Set.of() :
                commentLikeMapper.selectList(new LambdaQueryWrapper<CommentLike>()
                                .eq(CommentLike::getUserId, currentUser.id())
                                .in(CommentLike::getCommentId, comments.stream().map(Comment::getId).toList()))
                        .stream()
                        .map(CommentLike::getCommentId)
                        .collect(Collectors.toSet());
        // authorView 的三张字典表同样批量补齐
        Set<Long> schoolIds = new HashSet<>();
        Set<Long> collegeIds = new HashSet<>();
        Set<Long> majorIds = new HashSet<>();
        users.values().forEach(user -> {
            if (user.getSchoolId() != null) {
                schoolIds.add(user.getSchoolId());
            }
            if (user.getCollegeId() != null) {
                collegeIds.add(user.getCollegeId());
            }
            if (user.getMajorId() != null) {
                majorIds.add(user.getMajorId());
            }
        });
        Map<Long, School> schools = schoolIds.isEmpty() ? Map.of() : schoolMapper.selectList(new LambdaQueryWrapper<School>().in(School::getId, schoolIds))
                .stream().collect(Collectors.toMap(School::getId, Function.identity(), (a, b) -> a));
        Map<Long, College> colleges = collegeIds.isEmpty() ? Map.of() : collegeMapper.selectList(new LambdaQueryWrapper<College>().in(College::getId, collegeIds))
                .stream().collect(Collectors.toMap(College::getId, Function.identity(), (a, b) -> a));
        Map<Long, Major> majors = majorIds.isEmpty() ? Map.of() : majorMapper.selectList(new LambdaQueryWrapper<Major>().in(Major::getId, majorIds))
                .stream().collect(Collectors.toMap(Major::getId, Function.identity(), (a, b) -> a));
        return comments.stream()
                .map(comment -> new PostDtos.CommentView(
                        comment.getId(), comment.getPostId(), comment.getUserId(), comment.getParentId(), comment.getContent(),
                        safe(comment.getLikeCount()), safe(comment.getReplyCount()), likedCommentIds.contains(comment.getId()), Boolean.TRUE.equals(comment.getFeatured()),
                        comment.getStatus(), AppTime.toOffset(comment.getCreatedAt()), authorView(users.get(comment.getUserId()), schools, colleges, majors)
                ))
                .toList();
    }

    /**
     * 带缓存字典表的 authorView，供批量装配使用。
     * 调用方负责把 schoolIds / collegeIds / majorIds 一次性查成 Map 传进来。
     */
    private PostDtos.AuthorView authorView(User user, Map<Long, School> schools, Map<Long, College> colleges, Map<Long, Major> majors) {
        if (user == null) {
            return new PostDtos.AuthorView(null, "00000000", "匿名同学", null, "USER", "student", 1, "萌新探路员", null, null, null, null, false);
        }
        School school = user.getSchoolId() == null ? null : schools.get(user.getSchoolId());
        College college = user.getCollegeId() == null ? null : colleges.get(user.getCollegeId());
        Major major = user.getMajorId() == null ? null : majors.get(user.getMajorId());
        return new PostDtos.AuthorView(
                user.getId(), user.getPublicUid(), user.getNickname(), user.getAvatarUrl(), user.getRole(), user.getOperatorScope(),
                user.getLevel() == null ? 1 : user.getLevel(), firstNonBlank(user.getLevelTitle(), "萌新探路员"),
                school == null ? null : school.getName(), college == null ? user.getCollege() : college.getName(),
                major == null ? user.getMajor() : major.getName(), user.getGrade(), presenceService.isOnline(user.getId())
        );
    }

    /** 单用户兜底版本（非批量路径仍可用），逐个查字典表。 */
    private PostDtos.AuthorView authorView(User user) {
        Map<Long, School> schools = user == null || user.getSchoolId() == null ? Map.of() :
                schoolMapper.selectList(new LambdaQueryWrapper<School>().in(School::getId, user.getSchoolId()))
                        .stream().collect(Collectors.toMap(School::getId, Function.identity(), (a, b) -> a));
        Map<Long, College> colleges = user == null || user.getCollegeId() == null ? Map.of() :
                collegeMapper.selectList(new LambdaQueryWrapper<College>().in(College::getId, user.getCollegeId()))
                        .stream().collect(Collectors.toMap(College::getId, Function.identity(), (a, b) -> a));
        Map<Long, Major> majors = user == null || user.getMajorId() == null ? Map.of() :
                majorMapper.selectList(new LambdaQueryWrapper<Major>().in(Major::getId, user.getMajorId()))
                        .stream().collect(Collectors.toMap(Major::getId, Function.identity(), (a, b) -> a));
        return authorView(user, schools, colleges, majors);
    }

    private void saveImages(Long postId, List<String> images, LocalDateTime now) {
        if (images == null) {
            return;
        }
        int order = 0;
        for (String imageUrl : images.stream().filter(url -> url != null && !url.isBlank()).limit(9).toList()) {
            PostImage image = new PostImage();
            image.setPostId(postId);
            image.setUrl(imageUrl);
            image.setSortOrder(order++);
            image.setCreatedAt(now);
            imageMapper.insert(image);
        }
    }

    private void upsertDailyStat(Long postId, int likes, int comments, int shares) {
        // Historical additions, not current stock: undo does not erase a past action.
        dailyStatMapper.increment(postId, AppTime.nowLocal().toLocalDate(), likes, comments, shares);
    }

    private void grantXp(User user, int amount) {
        experienceService.award(user.getId(), amount);
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new NoSuchElementException("用户不存在");
        }
        return user;
    }

    private User requireRewardUser(Long userId) {
        User user = userMapper.lockById(userId);
        if (user == null || !"active".equals(user.getStatus())) {
            throw new AccessDeniedException("账号不可用");
        }
        return user;
    }

    private String normalizeRequestKey(String value) {
        if (value == null) { return null; }
        if (!value.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new IllegalArgumentException("发送标识格式不正确");
        }
        return value;
    }

    private Post requireMutablePost(Long postId, CurrentUser viewer) {
        Post post = postMapper.lockById(postId);
        if (post == null || !"published".equals(post.getStatus())) { throw new NoSuchElementException("PO 不存在"); }
        requireNotBlocked(post, viewer);
        return post;
    }

    private Comment requireMutableComment(Long commentId, CurrentUser viewer) {
        Comment locator = commentMapper.selectById(commentId);
        if (locator == null || locator.getPostId() == null) { throw new NoSuchElementException("评论不存在"); }
        requireMutablePost(locator.getPostId(), viewer);
        Comment current = commentMapper.lockById(commentId);
        if (current == null || !Objects.equals(current.getPostId(), locator.getPostId()) || "hidden".equals(current.getStatus())) {
            throw new NoSuchElementException("评论不存在");
        }
        Set<Long> blockedAuthors = viewer == null ? Set.of() : blockQueryApi.blockedUserIds(viewer.id());
        if (blockedAuthors.contains(current.getUserId())) { throw new NoSuchElementException("评论不存在"); }
        return current;
    }

    private void requireAuthenticated(CurrentUser currentUser) {
        if (currentUser == null || currentUser.id() == null) {
            throw new AuthenticationCredentialsNotFoundException("请先登录");
        }
    }

    private Post requirePost(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null || !"published".equals(post.getStatus())) {
            throw new NoSuchElementException("PO 不存在");
        }
        return post;
    }

    private Comment requirePostComment(Long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null || comment.getPostId() == null || "hidden".equals(comment.getStatus())) {
            throw new NoSuchElementException("评论不存在");
        }
        requirePost(comment.getPostId());
        return comment;
    }


    private boolean canPostOfficial(User user, String board) {
        if ("ADMIN".equals(user.getRole())) {
            return true;
        }
        return switch (firstNonBlank(user.getOperatorScope(), "")) {
            case "school" -> Set.of("recommend", "school").contains(normalizeBoard(board));
            case "college" -> "college".equals(normalizeBoard(board));
            case "major" -> "major".equals(normalizeBoard(board));
            default -> false;
        };
    }

    private void validateBoard(String board) {
        if (!BOARDS.contains(normalizeBoard(board))) {
            throw new IllegalArgumentException("板块不存在");
        }
    }

    private String normalizeBoard(String board) {
        return firstNonBlank(board, "recommend").trim().toLowerCase(Locale.ROOT);
    }

    private String excerpt(String content) {
        String text = content == null ? "" : content.trim();
        return text.length() <= 140 ? text : text.substring(0, 140) + "...";
    }

    private String joinTags(List<String> tags) {
        if (tags == null) {
            return "";
        }
        return tags.stream().filter(tag -> tag != null && !tag.isBlank()).map(String::trim).distinct().limit(8).collect(Collectors.joining(","));
    }

    private List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(",")).map(String::trim).filter(tag -> !tag.isBlank()).toList();
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }
}
