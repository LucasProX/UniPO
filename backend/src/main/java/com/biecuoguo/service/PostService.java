package com.biecuoguo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.domain.*;
import com.biecuoguo.dto.PostDtos;
import com.biecuoguo.mapper.*;
import com.biecuoguo.security.CurrentUser;
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
        User viewer = currentUser == null ? defaultViewer() : userMapper.selectById(currentUser.id());
        LambdaQueryWrapper<Post> wrapper = baseVisibleWrapper(viewer);
        applyTab(wrapper, normalizeBoard(tab), viewer);
        wrapper.orderByDesc(Post::getPinned)
                .orderByDesc(Post::getOfficial)
                .orderByDesc(Post::getDontMiss)
                .orderByDesc(Post::getLikeCount)
                .orderByDesc(Post::getCommentCount)
                .orderByDesc(Post::getPublishedAt);
        return toViews(postMapper.selectList(wrapper), currentUser);
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
        PostDailyStat stat = dailyStatMapper.selectList(new LambdaQueryWrapper<PostDailyStat>()
                        .eq(PostDailyStat::getStatDate, today)
                        .orderByDesc(PostDailyStat::getLikeCount)
                        .orderByDesc(PostDailyStat::getCommentCount))
                .stream()
                .max(Comparator.comparingInt(item -> safe(item.getLikeCount()) + safe(item.getCommentCount())))
                .orElse(null);
        if (stat != null) {
            Post post = postMapper.selectById(stat.getPostId());
            if (post != null) {
                return toView(post, currentUser);
            }
        }
        return list("recommend", currentUser).stream()
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
        post.setDeadlineAt(request.deadlineAt());
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
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));

        List<PostDtos.InteractionNoticeView> notices = new ArrayList<>();
        likes.forEach(item -> {
            User actor = users.get(item.getUserId());
            Post post = postMap.get(item.getPostId());
            if (actor != null && post != null) {
                notices.add(new PostDtos.InteractionNoticeView(
                        item.getId(), "like", authorView(actor), toView(post, currentUser), null, null, item.getCreatedAt(), true
                ));
            }
        });
        favorites.forEach(item -> {
            User actor = users.get(item.getUserId());
            Post post = postMap.get(item.getPostId());
            if (actor != null && post != null) {
                notices.add(new PostDtos.InteractionNoticeView(
                        item.getId(), "favorite", authorView(actor), toView(post, currentUser), null, null, item.getCreatedAt(), true
                ));
            }
        });
        comments.forEach(item -> {
            User actor = users.get(item.getUserId());
            Post post = postMap.get(item.getPostId());
            if (actor != null && post != null) {
                notices.add(new PostDtos.InteractionNoticeView(
                        item.getId(), "comment", authorView(actor), toView(post, currentUser), item.getContent(), item.getParentId(), item.getCreatedAt(), true
                ));
            }
        });
        commentReplies.forEach(item -> {
            User actor = users.get(item.getUserId());
            Post post = postMap.get(item.getPostId());
            if (actor != null && post != null) {
                notices.add(new PostDtos.InteractionNoticeView(
                        item.getId(), "reply", authorView(actor), toView(post, currentUser), item.getContent(), item.getParentId(), item.getCreatedAt(), true
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
        return toViews(postMapper.selectList(new LambdaQueryWrapper<Post>().in(Post::getId, ids).orderByDesc(Post::getPublishedAt)), currentUser);
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
        return posts.stream().map(post -> toView(post, currentUser)).toList();
    }

    private PostDtos.PostView toView(Post post, CurrentUser currentUser) {
        User author = userMapper.selectById(post.getAuthorId());
        List<String> images = imageMapper.selectList(new LambdaQueryWrapper<PostImage>().eq(PostImage::getPostId, post.getId()).orderByAsc(PostImage::getSortOrder))
                .stream().map(PostImage::getUrl).toList();
        boolean liked = currentUser != null && likeMapper.selectCount(new LambdaQueryWrapper<PostLike>().eq(PostLike::getPostId, post.getId()).eq(PostLike::getUserId, currentUser.id())) > 0;
        boolean favorited = currentUser != null && favoriteMapper.selectCount(new LambdaQueryWrapper<PostFavorite>().eq(PostFavorite::getPostId, post.getId()).eq(PostFavorite::getUserId, currentUser.id())) > 0;
        return new PostDtos.PostView(
                post.getId(), post.getBoard(), post.getSourceType(), post.getTitle(), post.getContent(), post.getExcerpt(),
                post.getCoverUrl(), images, post.getRiskLevel(), post.getOfficial(), post.getDontMiss(), post.getPinned(),
                post.getDeadlineAt(), post.getMissConsequence(), post.getNextAction(), splitTags(post.getTags()),
                safe(post.getLikeCount()), safe(post.getCommentCount()), safe(post.getFavoriteCount()), safe(post.getShareCount()),
                liked, favorited, post.getStatus(), post.getPublishedAt(), post.getCreatedAt(), authorView(author)
        );
    }

    private List<PostDtos.CommentView> commentViews(List<Comment> comments, CurrentUser currentUser) {
        Set<Long> userIds = comments.stream().map(Comment::getUserId).collect(Collectors.toSet());
        Map<Long, User> users = userIds.isEmpty() ? Map.of() : userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getId, userIds))
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        Set<Long> likedCommentIds = currentUser == null || comments.isEmpty() ? Set.of() :
                commentLikeMapper.selectList(new LambdaQueryWrapper<CommentLike>()
                                .eq(CommentLike::getUserId, currentUser.id())
                                .in(CommentLike::getCommentId, comments.stream().map(Comment::getId).toList()))
                        .stream()
                        .map(CommentLike::getCommentId)
                        .collect(Collectors.toSet());
        return comments.stream()
                .map(comment -> new PostDtos.CommentView(
                        comment.getId(), comment.getPostId(), comment.getUserId(), comment.getParentId(), comment.getContent(),
                        safe(comment.getLikeCount()), safe(comment.getReplyCount()), likedCommentIds.contains(comment.getId()), Boolean.TRUE.equals(comment.getFeatured()),
                        comment.getStatus(), comment.getCreatedAt(), authorView(users.get(comment.getUserId()))
                ))
                .toList();
    }

    private PostDtos.AuthorView authorView(User user) {
        if (user == null) {
            return new PostDtos.AuthorView(null, "00000000", "匿名同学", null, "USER", "student", 1, "萌新探路员", null, null, null, null, false);
        }
        School school = user.getSchoolId() == null ? null : schoolMapper.selectById(user.getSchoolId());
        College college = user.getCollegeId() == null ? null : collegeMapper.selectById(user.getCollegeId());
        Major major = user.getMajorId() == null ? null : majorMapper.selectById(user.getMajorId());
        return new PostDtos.AuthorView(
                user.getId(), user.getPublicUid(), user.getNickname(), user.getAvatarUrl(), user.getRole(), user.getOperatorScope(),
                user.getLevel() == null ? 1 : user.getLevel(), firstNonBlank(user.getLevelTitle(), "萌新探路员"),
                school == null ? null : school.getName(), college == null ? user.getCollege() : college.getName(),
                major == null ? user.getMajor() : major.getName(), user.getGrade(), presenceService.isOnline(user.getId())
        );
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
