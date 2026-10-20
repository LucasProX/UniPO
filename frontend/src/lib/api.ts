import axios from 'axios';
import { api, localApi, publicApi, unwrap, apiErrorMessage, apiErrorCode } from './http';
import type { AuthSessionView, BoardView, CheckInView, CommentView, ConversationView, InteractionNoticeView, MessageView, PagedResponse, PostView, PublicProfileView, SpotlightView, UserProfile, UserStats, ApiResponse } from '../types';

/**
 * 业务端点。按领域分组，新增接口时只动对应分组的文件/段落。
 *
 * 基础设施（axios、Cookie、CSRF、错误包）在 ./http.ts；
 * 这里不写拦截器、不写 token，只描述「调哪个路径、拿什么类型」。
 */
export { apiErrorMessage, apiErrorCode, apiErrorRequestId, isAuthError, UNAUTHORIZED_EVENT, csrfToken, API_PREFIX } from './http';

/**
 * 登录专用：把 axios 错误转成带 code 的 Error，供视图层区分
 * 「密码错」和「网络断了」——message 只用来展示，不该拿来做分支。
 */
async function unwrapLogin(request: Promise<{ data: ApiResponse<AuthSessionView> }>) {
  try {
    return await unwrap(request);
  } catch (error) {
    const code = apiErrorCode(error);
    const message = apiErrorMessage(error, '登录失败');
    const wrapped = new Error(message) as Error & { code?: string | null; cause?: unknown };
    wrapped.code = code;
    // 保留原始错误，登录兜底逻辑要靠它判断「是网络不通还是服务端拒绝」。
    wrapped.cause = error;
    throw wrapped;
  }
}

async function loginWithFallback(email: string, password: string) {
  try {
    return await unwrapLogin(api.post('/auth/login', { email, password }));
  } catch (primaryError) {
    const cause = (primaryError as { cause?: unknown } | null)?.cause;
    const shouldTryLocalApi = import.meta.env.DEV && axios.isAxiosError(cause) && !cause.response;
    if (!shouldTryLocalApi) throw primaryError;
    try {
      return await unwrapLogin(localApi.post('/auth/login', { email, password }));
    } catch (fallbackError) {
      throw fallbackError instanceof Error ? fallbackError : primaryError;
    }
  }
}

export const campusApi = {
  /** 老的全量列表，仍保留：下拉刷新 / 兜底场景用。 */
  posts: (tab = 'recommend') => unwrap<PostView[]>(api.get('/posts', { params: { tab } })),
  /**
   * 游标分页列表（推荐用法）。limit 默认 20、上限 50；cursor 传上一页的 nextCursor。
   */
  postsPage: (tab = 'recommend', cursor?: string | null, limit = 20) =>
    unwrap<PagedResponse<PostView>>(api.get('/posts/page', {
      params: { tab, ...(cursor ? { cursor } : {}), limit }
    })),
  boards: () => unwrap<BoardView[]>(api.get('/posts/boards')),
  /** 首页轮播位；空数组时前端不渲染（不造假数据）。 */
  spotlights: () => unwrap<SpotlightView[]>(api.get('/spotlights')),
  hotToday: () => unwrap<PostView>(api.get('/posts/hot-today')),
  dontMiss: () => unwrap<PostView[]>(api.get('/posts/dont-miss')),
  interactionNotices: () => unwrap<InteractionNoticeView[]>(api.get('/posts/interaction-notices')),
  comments: (postId: number) => unwrap<CommentView[]>(api.get(`/posts/${postId}/comments`)),
  comment: (postId: number, payload: { content: string; parentId?: number | null }) =>
    unwrap<CommentView>(api.post(`/posts/${postId}/comments`, payload)),
  like: (postId: number) => unwrap<boolean>(api.post(`/posts/${postId}/like`)),
  unlike: (postId: number) => unwrap<boolean>(api.delete(`/posts/${postId}/like`)),
  likeComment: (commentId: number) => unwrap<boolean>(api.post(`/posts/comments/${commentId}/like`)),
  unlikeComment: (commentId: number) => unwrap<boolean>(api.delete(`/posts/comments/${commentId}/like`)),
  favorite: (postId: number) => unwrap<boolean>(api.post(`/posts/${postId}/favorite`)),
  unfavorite: (postId: number) => unwrap<boolean>(api.delete(`/posts/${postId}/favorite`)),
  share: (postId: number) => unwrap<{ url: string; shareCount: number }>(api.post(`/posts/${postId}/share`)),
  me: () => unwrap<UserProfile>(api.get('/auth/me')),
  stats: () => unwrap<UserStats>(api.get('/users/me/stats')),
  checkInStatus: () => unwrap<CheckInView>(api.get('/users/me/check-in')),
  checkIn: () => unwrap<CheckInView>(api.post('/users/me/check-in')),
  myPosts: () => unwrap<PostView[]>(api.get('/users/me/posts')),
  myLikes: () => unwrap<PostView[]>(api.get('/users/me/likes')),
  myFavorites: () => unwrap<PostView[]>(api.get('/users/me/post-favorites')),
  conversations: () => unwrap<ConversationView[]>(api.get('/messages/conversations')),
  messages: (conversationId: number) => unwrap<MessageView[]>(api.get(`/messages/conversations/${conversationId}/messages`)),
  unreadCount: () => unwrap<number>(api.get('/messages/unread-count')),
  userProfile: (uid: string) => unwrap<PublicProfileView>(api.get(`/users/${uid}`)),
  userPosts: (uid: string) => unwrap<PostView[]>(api.get(`/users/${uid}/posts`)),
  userLikes: (uid: string) => unwrap<PostView[]>(api.get(`/users/${uid}/likes`)),
  userFavorites: (uid: string) => unwrap<PostView[]>(api.get(`/users/${uid}/post-favorites`)),
  follow: (uid: string) => unwrap<boolean>(api.post(`/users/${uid}/follow`)),
  unfollow: (uid: string) => unwrap<boolean>(api.delete(`/users/${uid}/follow`)),
  createConversation: (peerUid: string) => unwrap<ConversationView>(api.post('/messages/conversations', { peerUid })),
  sendMessage: (conversationId: number, content: string) =>
    unwrap<MessageView>(api.post(`/messages/conversations/${conversationId}/messages`, { content }))
};

export const authApi = {
  login: loginWithFallback,
  // Cookie 会话在服务端，退出只需要让后端吊销它并清 Cookie；不再传 refresh token。
  logout: () => unwrap<boolean>(api.post('/auth/logout', {})),
  heartbeat: () => unwrap<boolean>(api.post('/auth/heartbeat')),
  register: (payload: { email: string; password: string; confirmPassword: string; nickname: string; verificationCode: string }) =>
    unwrap<AuthSessionView>(publicApi.post('/auth/register', payload)),
  requestVerificationCode: (payload: { email: string; purpose: 'register' | 'reset-password' }) =>
    unwrap<boolean>(publicApi.post('/auth/verification-code', payload)),
  emailAvailability: (email: string, purpose: 'register' | 'reset-password' = 'register') =>
    unwrap<{ available: boolean }>(publicApi.get('/auth/email-availability', { params: { email, purpose } })),
  nicknameAvailability: (nickname: string) =>
    unwrap<{ available: boolean }>(publicApi.get('/auth/nickname-availability', { params: { nickname } })),
  verifyVerificationCode: (payload: { email: string; purpose: 'register' | 'reset-password'; code: string }) =>
    unwrap<boolean>(publicApi.post('/auth/verification-code/verify', payload)),
  resetPassword: (payload: { email: string; verificationCode: string; password: string; confirmPassword: string }) =>
    unwrap<boolean>(publicApi.post('/auth/reset-password', payload))
};

export const postApi = {
  create: (payload: {
    board: string;
    title: string;
    content: string;
    coverUrl?: string;
    images?: string[];
    tags?: string[];
    official?: boolean;
    dontMiss?: boolean;
  }) => unwrap<PostView>(api.post('/posts', payload)),
  remove: (postId: number) => unwrap<boolean>(api.delete(`/posts/${postId}`))
};

export const userApi = {
  updateProfile: (payload: { nickname?: string; avatarUrl?: string; bio?: string }) => unwrap<UserProfile>(api.put('/users/me', payload))
};

export const mediaApi = {
  upload: (file: File, purpose = 'avatar') => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('purpose', purpose);
    return unwrap<{ url: string }>(api.post('/media/upload', formData));
  }
};
