package com.biecuoguo.service;

import com.biecuoguo.config.AppTime;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.domain.*;
import com.biecuoguo.dto.PostDtos;
import com.biecuoguo.mapper.*;
import com.biecuoguo.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PostService {
    private static final Set<String> BOARDS = Set.of("recommend", "school", "college", "major");

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

    public PostService(PostMapper postMapper, PostImageMapper imageMapper, PostLikeMapper likeMapper, PostFavoriteMapper favoriteMapper, PostShareMapper shareMapper, PostDailyStatMapper dailyStatMapper, CommentMapper commentMapper, CommentLikeMapper commentLikeMapper, UserMapper userMapper, SchoolMapper schoolMapper, CollegeMapper collegeMapper, MajorMapper majorMapper, PresenceService presenceService) {
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
        User viewer = currentUser == null ? defaultViewer() : userMapper.selectById(currentUser.id());
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
        User viewer = currentUser == null ? defaultViewer() : userMapper.selectById(currentUser.id());
        return List.of(
                new PostDtos.BoardView("recommend", "推荐", "按热度、官方权重和发布时间混排", countForBoard("recommend", viewer)),
                new PostDtos.BoardView("school", "校 PO", "全校同学都能看到的校园动态", countForBoard("school", viewer)),
                new PostDtos.BoardView("college", "院 PO", "同学院范围内更贴近课程和活动", countForBoard("college", viewer)),
                new PostDtos.BoardView("major", "专业 PO", "专业相关经验、作业、竞赛和提醒", countForBoard("major", viewer))
        );
    }

    public PostDtos.PostView hotToday(CurrentUser currentUser) {
        LocalDate today = LocalDate.now();
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
        User viewer = currentUser == null ? defaultViewer() : userMapper.selectById(currentUser.id());
        LambdaQueryWrapper<Post> wrapper = baseVisibleWrapper(viewer)
                .eq(Post::getDontMiss, true)
                .orderByDesc(Post::getPinned)
                .orderByAsc(Post::getDeadlineAt);
        return toViews(postMapper.selectList(wrapper), currentUser);
    }

    @Transactional
    public PostDtos.PostView create(PostDtos.CreatePostRequest request, CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        User author = requireUser(currentUser.id());
        validateBoard(request.board());
        if (author.getSchoolId() == null) {
            throw new IllegalArgumentException("请先在个人中心选择学校");
        }
        LocalDateTime now = LocalDateTime.now();
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
        Post post = requirePost(postId);
        if (!Objects.equals(post.getAuthorId(), currentUser.id())) {
            throw new AccessDeniedException("只能删除自己的 PO");
        }
        post.setStatus("deleted");
        post.setUpdatedAt(LocalDateTime.now());
        postMapper.updateById(post);
        return true;
    }

    public List<PostDtos.CommentView> comments(Long postId, CurrentUser currentUser) {
        Post post = requirePost(postId);
        List<Comment> comments = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getPostId, post.getId())
                .ne(Comment::getStatus, "hidden")
                .orderByDesc(Comment::getFeatured)
                .orderByAsc(Comment::getCreatedAt));
        return commentViews(comments, currentUser);
    }

    @Transactional
    public PostDtos.CommentView comment(Long postId, PostDtos.CreateCommentRequest request, CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        Post post = requirePost(postId);
        User author = requireUser(currentUser.id());
        LocalDateTime now = LocalDateTime.now();
        Comment comment = new Comment();
        comment.setPostId(post.getId());
        comment.setUserId(author.getId());
        comment.setParentId(request.parentId());
        comment.setContent(request.content().trim());
        comment.setLikeCount(0);
        comment.setReplyCount(0);
        comment.setFeatured(false);
        comment.setStatus("visible");
        comment.setCreatedAt(now);
        comment.setUpdatedAt(now);
        commentMapper.insert(comment);
        post.setCommentCount(safe(post.getCommentCount()) + 1);
        post.setUpdatedAt(now);
        postMapper.updateById(post);
        upsertDailyStat(post.getId(), 0, 1, 0);
        grantXp(author, 4);
        return commentViews(List.of(comment), currentUser).get(0);
    }

    @Transactional
    public boolean likeComment(Long commentId, CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        Comment comment = requirePostComment(commentId);
        CommentLike existing = commentLikeMapper.selectOne(new LambdaQueryWrapper<CommentLike>()
                .eq(CommentLike::getCommentId, commentId)
                .eq(CommentLike::getUserId, currentUser.id()));
        if (existing != null) {
            return true;
        }
        CommentLike like = new CommentLike();
        like.setCommentId(commentId);
        like.setUserId(currentUser.id());
        like.setCreatedAt(LocalDateTime.now());
        commentLikeMapper.insert(like);
        comment.setLikeCount(safe(comment.getLikeCount()) + 1);
        comment.setUpdatedAt(LocalDateTime.now());
        commentMapper.updateById(comment);
        return true;
    }

    @Transactional
    public boolean unlikeComment(Long commentId, CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        Comment comment = requirePostComment(commentId);
        int deleted = commentLikeMapper.delete(new LambdaQueryWrapper<CommentLike>()
                .eq(CommentLike::getCommentId, commentId)
                .eq(CommentLike::getUserId, currentUser.id()));
        if (deleted > 0) {
            comment.setLikeCount(Math.max(0, safe(comment.getLikeCount()) - 1));
            comment.setUpdatedAt(LocalDateTime.now());
            commentMapper.updateById(comment);
        }
        return true;
    }

    @Transactional
    public boolean like(Long postId, CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        Post post = requirePost(postId);
        Long userId = currentUser.id();
        PostLike existing = likeMapper.selectOne(new LambdaQueryWrapper<PostLike>().eq(PostLike::getPostId, postId).eq(PostLike::getUserId, userId));
        if (existing != null) {
            return true;
        }
        PostLike like = new PostLike();
        like.setPostId(postId);
        like.setUserId(userId);
        like.setCreatedAt(LocalDateTime.now());
        likeMapper.insert(like);
        post.setLikeCount(safe(post.getLikeCount()) + 1);
        postMapper.updateById(post);
        upsertDailyStat(postId, 1, 0, 0);
        return true;
    }

    @Transactional
    public boolean unlike(Long postId, CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        Post post = requirePost(postId);
        int deleted = likeMapper.delete(new LambdaQueryWrapper<PostLike>().eq(PostLike::getPostId, postId).eq(PostLike::getUserId, currentUser.id()));
        if (deleted > 0) {
            post.setLikeCount(Math.max(0, safe(post.getLikeCount()) - 1));
            postMapper.updateById(post);
        }
        return true;
    }

    @Transactional
    public boolean favorite(Long postId, CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        Post post = requirePost(postId);
        PostFavorite existing = favoriteMapper.selectOne(new LambdaQueryWrapper<PostFavorite>().eq(PostFavorite::getPostId, postId).eq(PostFavorite::getUserId, currentUser.id()));
        if (existing != null) {
            return true;
        }
        PostFavorite favorite = new PostFavorite();
        favorite.setPostId(postId);
        favorite.setUserId(currentUser.id());
        favorite.setCreatedAt(LocalDateTime.now());
        favoriteMapper.insert(favorite);
        post.setFavoriteCount(safe(post.getFavoriteCount()) + 1);
        postMapper.updateById(post);
        return true;
    }

    @Transactional
    public boolean unfavorite(Long postId, CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        Post post = requirePost(postId);
        int deleted = favoriteMapper.delete(new LambdaQueryWrapper<PostFavorite>().eq(PostFavorite::getPostId, postId).eq(PostFavorite::getUserId, currentUser.id()));
        if (deleted > 0) {
            post.setFavoriteCount(Math.max(0, safe(post.getFavoriteCount()) - 1));
            postMapper.updateById(post);
        }
        return true;
    }

    @Transactional
    public PostDtos.ShareResponse share(Long postId, CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        Post post = requirePost(postId);
        PostShare share = new PostShare();
        share.setPostId(postId);
        share.setUserId(currentUser.id());
        share.setChannel("copy_link");
        share.setCreatedAt(LocalDateTime.now());
        shareMapper.insert(share);
        post.setShareCount(safe(post.getShareCount()) + 1);
        postMapper.updateById(post);
        upsertDailyStat(postId, 0, 0, 1);
        return new PostDtos.ShareResponse("/posts/" + postId, post.getShareCount());
    }

    public List<PostDtos.PostView> userPosts(Long userId, CurrentUser currentUser) {
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
        List<Long> ids = favoriteMapper.selectList(new LambdaQueryWrapper<PostFavorite>().eq(PostFavorite::getUserId, user.getId()))
                .stream().map(PostFavorite::getPostId).toList();
        return postsByIds(ids, currentUser);
    }

    public List<PostDtos.InteractionNoticeView> interactionNotices(CurrentUser currentUser) {
        requireAuthenticated(currentUser);
        Long currentUserId = currentUser.id();
        List<Post> myPosts = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getAuthorId, currentUserId)
                .eq(Post::getStatus, "published"));
        Map<Long, Post> postMap = myPosts.stream().collect(Collectors.toMap(Post::getId, Function.identity()));
        List<Long> postIds = new ArrayList<>(postMap.keySet());

        List<PostLike> likes = postIds.isEmpty() ? List.of() : likeMapper.selectList(new LambdaQueryWrapper<PostLike>()
                .in(PostLike::getPostId, postIds)
                .ne(PostLike::getUserId, currentUserId)
                .orderByDesc(PostLike::getCreatedAt));
        List<PostFavorite> favorites = postIds.isEmpty() ? List.of() : favoriteMapper.selectList(new LambdaQueryWrapper<PostFavorite>()
                .in(PostFavorite::getPostId, postIds)
                .ne(PostFavorite::getUserId, currentUserId)
                .orderByDesc(PostFavorite::getCreatedAt));
        List<Comment> comments = postIds.isEmpty() ? List.of() : commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .in(Comment::getPostId, postIds)
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
        return toViews(postMapper.selectList(new LambdaQueryWrapper<Post>()
                .in(Post::getId, ids)
                .eq(Post::getStatus, "published")
                .orderByDesc(Post::getPublishedAt)), currentUser);
    }

    private LambdaQueryWrapper<Post> baseVisibleWrapper(User viewer) {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<Post>().eq(Post::getStatus, "published");
        if (viewer != null && viewer.getSchoolId() != null) {
            wrapper.eq(Post::getSchoolId, viewer.getSchoolId());
        } else {
            wrapper.eq(Post::getSchoolId, 1L);
        }
        return wrapper;
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
                        authorView(authors.get(post.getAuthorId()), schools, colleges, majors)
                ))
                .toList();
    }

    private PostDtos.PostView toView(Post post, CurrentUser currentUser) {
        return toViews(List.of(post), currentUser).stream().findFirst().orElse(null);
    }

    private List<PostDtos.CommentView> commentViews(List<Comment> comments, CurrentUser currentUser) {
        if (comments.isEmpty()) {
            return List.of();
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
        LocalDate today = LocalDate.now();
        PostDailyStat stat = dailyStatMapper.selectOne(new LambdaQueryWrapper<PostDailyStat>().eq(PostDailyStat::getPostId, postId).eq(PostDailyStat::getStatDate, today));
        if (stat == null) {
            stat = new PostDailyStat();
            stat.setPostId(postId);
            stat.setStatDate(today);
            stat.setLikeCount(Math.max(0, likes));
            stat.setCommentCount(Math.max(0, comments));
            stat.setShareCount(Math.max(0, shares));
            dailyStatMapper.insert(stat);
        } else {
            stat.setLikeCount(safe(stat.getLikeCount()) + likes);
            stat.setCommentCount(safe(stat.getCommentCount()) + comments);
            stat.setShareCount(safe(stat.getShareCount()) + shares);
            dailyStatMapper.updateById(stat);
        }
    }

    private void grantXp(User user, int amount) {
        user.setXp(safe(user.getXp()) + amount);
        int level = Math.max(1, Math.min(100, user.getXp() / 40 + 1));
        user.setLevel(level);
        user.setLevelTitle(levelTitle(level));
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
    }

    private String levelTitle(int level) {
        return switch ((Math.max(1, Math.min(100, level)) - 1) / 10) {
            case 0 -> "萌新探路员";
            case 1 -> "早八幸存者";
            case 2 -> "选课赌神";
            case 3 -> "食堂测评官";
            case 4 -> "图书馆钉子户";
            case 5 -> "Deadline 驯服者";
            case 6 -> "校园情报员";
            case 7 -> "学分炼金术士";
            case 8 -> "毕设渡劫人";
            default -> "传说中的不鸽王";
        };
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new NoSuchElementException("用户不存在");
        }
        return user;
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

    private User defaultViewer() {
        return userMapper.selectList(new LambdaQueryWrapper<User>().eq(User::getStatus, "active").last("limit 1")).stream().findFirst().orElse(null);
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
