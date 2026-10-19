import { defineStore } from 'pinia';
import { ref } from 'vue';
import { campusApi, postApi } from '../lib/api';
import { boardLabel, defaultBoards, describeError, enrichApiPosts, normalizeBoards } from '../lib/postHelpers';
import { useAuthStore } from './auth';
import { useMessagesStore } from './messages';
import { useProfileStore } from './profile';
import type { BoardCode, BoardView, CampusComment, CampusPost, CommentView, PostView } from '../types';

/**
 * 信息流（帖子 / 板块 / 评论）+ 帖子互动（点赞收藏评论删除）。
 * Web 与 App 两端共用。帖子详情弹窗的 UI 态也在这里，因为多个视图都要打开它。
 */
export const useFeedStore = defineStore('feed', () => {
  const auth = useAuthStore();
  const messages = useMessagesStore();
  const profile = useProfileStore();

  const posts = ref<CampusPost[]>([]);
  const boards = ref<BoardView[]>(defaultBoards());
  const comments = ref<CampusComment[]>([]);
  const feedLoading = ref(false);
  const feedError = ref('');
  const selectedPost = ref<CampusPost | null>(null);
  const expandedPost = ref<CampusPost | null>(null);

  const deletingPostIds = ref<Set<number>>(new Set());

  // 评论区 UI 态
  const showPostCommentBox = ref(false);
  const newCommentText = ref('');
  const replyTargetId = ref<number | null>(null);
  const replyText = ref('');
  const expandedReplyCounts = ref<Record<number, number>>({});

  let activePostOpenToken = 0;

  function boardName(board: BoardCode) {
    return boardLabel(board, boards.value);
  }

  function commentsForPost(post: CampusPost) {
    return comments.value.filter((comment) => comment.postId === post.id);
  }

  function normalizeComment(comment: CommentView): CampusComment {
    return { ...comment, liked: Boolean(comment.liked) };
  }

  function authorFromCurrentUser() {
    const user = auth.currentUser;
    return {
      id: user.id,
      uid: user.publicUid,
      nickname: user.nickname,
      avatarUrl: user.avatarUrl,
      role: user.role,
      operatorScope: user.operatorScope,
      level: user.level,
      levelTitle: user.levelTitle,
      school: '试点大学',
      college: user.college,
      major: user.major,
      grade: user.grade,
      online: user.online
    };
  }

  /** 本地新建的帖子插入列表头部（发布成功后调用） */
  function addLocalPost(apiPost: PostView): CampusPost {
    const post = enrichApiPosts([apiPost])[0];
    posts.value = [post, ...posts.value];
    profile.profilePosts = [post, ...profile.profilePosts];
    return post;
  }

  async function load() {
    feedLoading.value = true;
    feedError.value = '';
    try {
      const [postList, boardList] = await Promise.all([
        campusApi.posts('recommend'),
        campusApi.boards()
      ]);
      posts.value = enrichApiPosts(postList);
      boards.value = boardList.length ? normalizeBoards(boardList) : defaultBoards();
      comments.value = [];
      selectedPost.value = posts.value[0] || null;
    } catch (error) {
      posts.value = [];
      boards.value = defaultBoards();
      comments.value = [];
      selectedPost.value = null;
      feedError.value = describeError(error, '无法连接后端，请确认服务已启动');
    } finally {
      feedLoading.value = false;
    }
  }

  /** 用户资料变更后，把所有帖子/评论里的该作者信息同步刷新 */
  function syncAuthorEverywhere(userId: number, author: CampusComment['author']) {
    posts.value = posts.value.map((post) => post.author.id === userId ? { ...post, author } : post);
    comments.value = comments.value.map((comment) => comment.author.id === userId ? { ...comment, author } : comment);
    if (expandedPost.value?.author.id === userId) {
      expandedPost.value = { ...expandedPost.value, author };
    }
    profile.syncAuthor(userId, author);
  }

  async function refreshPostComments(postId: number) {
    try {
      const apiComments = await campusApi.comments(postId);
      comments.value = [
        ...comments.value.filter((comment) => comment.postId !== postId),
        ...apiComments.map(normalizeComment)
      ];
    } catch {
      // 评论加载失败保留旧数据，不打断主流程
    }
  }

  async function refreshPostFromServer(postId: number) {
    try {
      const apiPosts = await campusApi.posts('recommend');
      const freshPosts = enrichApiPosts(apiPosts);
      posts.value = posts.value.map((item) => freshPosts.find((fresh) => fresh.id === item.id) || item);
      const freshExpanded = freshPosts.find((post) => post.id === postId);
      if (freshExpanded && expandedPost.value?.id === postId) expandedPost.value = freshExpanded;
    } catch {
      // 刷新失败保留本地状态
    }
  }

  async function openPost(post: CampusPost) {
    const token = ++activePostOpenToken;
    selectedPost.value = post;
    expandedPost.value = post;
    showPostCommentBox.value = false;
    newCommentText.value = '';
    replyTargetId.value = null;
    replyText.value = '';
    await refreshPostComments(post.id);
    if (token === activePostOpenToken && expandedPost.value?.id === post.id) {
      expandedPost.value = posts.value.find((item) => item.id === post.id) || post;
    }
  }

  function closePost() {
    activePostOpenToken += 1;
    expandedPost.value = null;
  }

  const detailComments = ref<CampusComment[]>([]);
  const rootDetailComments = ref<CampusComment[]>([]);

  function syncDetailComments() {
    if (!expandedPost.value) {
      detailComments.value = [];
      rootDetailComments.value = [];
      return;
    }
    const list = commentsForPost(expandedPost.value);
    detailComments.value = list;
    rootDetailComments.value = list.filter((comment) => !comment.parentId);
  }

  function isDeleting(postId: number) {
    return deletingPostIds.value.has(postId);
  }

  function setPostDeleting(postId: number, deleting: boolean) {
    const next = new Set(deletingPostIds.value);
    if (deleting) next.add(postId);
    else next.delete(postId);
    deletingPostIds.value = next;
  }

  function canDeletePost(post: CampusPost) {
    return auth.hasSession && post.author.id === auth.currentUser.id;
  }

  async function deleteMyPost(post: CampusPost) {
    if (!auth.hasSession) {
      auth.openDialog('请先登录后删除自己的 PO');
      return;
    }
    if (!canDeletePost(post) || isDeleting(post.id)) return;
    if (!window.confirm('确定删除这条 PO 吗？删除后不会在首页和我的作品里展示。')) return;
    setPostDeleting(post.id, true);
    let ok = false;
    try {
      await postApi.remove(post.id);
      ok = true;
    } catch (error) {
      await auth.handleAuthSensitiveError(error, '登录状态失效，请重新登录后再试', '删除失败，请确认这是你自己的 PO 后再试');
    } finally {
      setPostDeleting(post.id, false);
    }
    if (!ok) return;
    removePostFromLocalState(post.id);
    auth.userStats.posts = Math.max(0, auth.userStats.posts - 1);
    auth.showNotice('已删除这条 PO');
    await messages.refreshConversations();
  }

  function removePostFromLocalState(postId: number) {
    posts.value = posts.value.filter((item) => item.id !== postId);
    profile.removePost(postId);
    comments.value = comments.value.filter((comment) => comment.postId !== postId);
    messages.interactionNotices = messages.interactionNotices.filter((notice) => notice.post?.id !== postId);
    if (selectedPost.value?.id === postId) selectedPost.value = posts.value[0] || null;
    if (expandedPost.value?.id === postId) expandedPost.value = null;
  }

  async function toggleLike(post: CampusPost) {
    if (!auth.hasSession) {
      auth.openDialog();
      return;
    }
    const nextLiked = !post.liked;
    try {
      if (nextLiked) await campusApi.like(post.id);
      else await campusApi.unlike(post.id);
    } catch (error) {
      if (await auth.handleAuthSensitiveError(error, '登录状态失效，请重新登录后再试')) return;
      return;
    }
    post.liked = nextLiked;
    post.likeCount = Math.max(0, post.likeCount + (nextLiked ? 1 : -1));
    auth.userStats.likedPosts = Math.max(0, auth.userStats.likedPosts + (nextLiked ? 1 : -1));
    profile.setLiked(post, nextLiked);
    await refreshPostFromServer(post.id);
    await messages.refreshConversations();
  }

  async function toggleFavorite(post: CampusPost) {
    if (!auth.hasSession) {
      auth.openDialog();
      return;
    }
    const nextFavorited = !post.favorited;
    try {
      if (nextFavorited) await campusApi.favorite(post.id);
      else await campusApi.unfavorite(post.id);
    } catch (error) {
      if (await auth.handleAuthSensitiveError(error, '登录状态失效，请重新登录后再试')) return;
      return;
    }
    post.favorited = nextFavorited;
    post.favoriteCount = Math.max(0, post.favoriteCount + (nextFavorited ? 1 : -1));
    auth.userStats.favorites = Math.max(0, auth.userStats.favorites + (nextFavorited ? 1 : -1));
    profile.setFavorited(post, nextFavorited);
    await refreshPostFromServer(post.id);
    await messages.refreshConversations();
  }

  function toggleCommentBox() {
    if (!auth.hasSession) {
      auth.openDialog('请先登录后评论 PO');
      return;
    }
    showPostCommentBox.value = !showPostCommentBox.value;
  }

  async function submitComment() {
    if (!expandedPost.value || !auth.hasSession) return;
    const content = newCommentText.value.trim().slice(0, 200);
    if (!content) return;
    let created: CommentView | null = null;
    try {
      created = await campusApi.comment(expandedPost.value.id, { content });
    } catch (error) {
      if (await auth.handleAuthSensitiveError(error, '登录状态失效，评论没有写入后端')) return;
      return;
    }
    if (!created) {
      auth.openDialog('登录状态失效，评论没有写入后端');
      return;
    }
    comments.value = [normalizeComment(created), ...comments.value];
    expandedPost.value.commentCount += 1;
    auth.userStats.comments += 1;
    syncDetailComments();
    await refreshPostFromServer(expandedPost.value.id);
    await messages.refreshConversations();
    newCommentText.value = '';
    showPostCommentBox.value = false;
  }

  function startReply(comment: CampusComment) {
    if (!auth.hasSession) {
      auth.openDialog('请先登录后回复评论');
      return;
    }
    replyTargetId.value = comment.id;
    replyText.value = '';
  }

  async function submitReply(parent: CampusComment) {
    if (!expandedPost.value || !auth.hasSession) return;
    const content = replyText.value.trim().slice(0, 200);
    if (!content) return;
    let created: CommentView | null = null;
    try {
      created = await campusApi.comment(expandedPost.value.id, { content, parentId: parent.id });
    } catch (error) {
      if (await auth.handleAuthSensitiveError(error, '登录状态失效，回复没有写入后端')) return;
      return;
    }
    if (!created) {
      auth.openDialog('登录状态失效，回复没有写入后端');
      return;
    }
    comments.value = [...comments.value, normalizeComment(created)];
    expandedPost.value.commentCount += 1;
    parent.replyCount += 1;
    syncDetailComments();
    await refreshPostFromServer(expandedPost.value.id);
    await messages.refreshConversations();
    expandedReplyCounts.value[parent.id] = Math.max(expandedReplyCounts.value[parent.id] || 0, Math.min(5, repliesFor(parent).length + 1));
    replyTargetId.value = null;
    replyText.value = '';
  }

  async function toggleCommentLike(comment: CampusComment) {
    if (!auth.hasSession) {
      auth.openDialog();
      return;
    }
    const nextLiked = !comment.liked;
    try {
      if (nextLiked) await campusApi.likeComment(comment.id);
      else await campusApi.unlikeComment(comment.id);
    } catch (error) {
      if (await auth.handleAuthSensitiveError(error, '登录状态失效，请重新登录后再试')) return;
      return;
    }
    comment.liked = nextLiked;
    comment.likeCount = Math.max(0, comment.likeCount + (nextLiked ? 1 : -1));
    comments.value = comments.value.map((item) => item.id === comment.id ? { ...item, liked: nextLiked, likeCount: comment.likeCount } : item);
    syncDetailComments();
  }

  function repliesFor(comment: CampusComment) {
    return detailComments.value.filter((item) => item.parentId === comment.id);
  }

  function expandedReplyCount(comment: CampusComment) {
    return expandedReplyCounts.value[comment.id] || 0;
  }

  function visibleReplies(comment: CampusComment) {
    return repliesFor(comment).slice(0, expandedReplyCount(comment));
  }

  function remainingReplyCount(comment: CampusComment) {
    return Math.max(0, repliesFor(comment).length - expandedReplyCount(comment));
  }

  function expandReplies(comment: CampusComment) {
    const current = expandedReplyCount(comment);
    const total = repliesFor(comment).length;
    expandedReplyCounts.value[comment.id] = Math.min(total, current + 5 || 5);
  }

  return {
    posts,
    boards,
    comments,
    feedLoading,
    feedError,
    selectedPost,
    expandedPost,
    detailComments,
    rootDetailComments,
    deletingPostIds,
    showPostCommentBox,
    newCommentText,
    replyTargetId,
    replyText,
    boardName,
    commentsForPost,
    authorFromCurrentUser,
    addLocalPost,
    load,
    syncAuthorEverywhere,
    refreshPostComments,
    refreshPostFromServer,
    openPost,
    closePost,
    syncDetailComments,
    isDeleting,
    canDeletePost,
    deleteMyPost,
    removePostFromLocalState,
    toggleLike,
    toggleFavorite,
    toggleCommentBox,
    submitComment,
    startReply,
    submitReply,
    toggleCommentLike,
    repliesFor,
    expandedReplyCount,
    visibleReplies,
    remainingReplyCount,
    expandReplies
  };
});
