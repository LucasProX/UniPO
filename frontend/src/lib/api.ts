import axios from 'axios';
import type { AxiosInstance } from 'axios';
import type { ApiErrorBody, ApiResponse, AuthSessionView, BoardView, CheckInView, CommentView, ConversationView, InteractionNoticeView, MessageView, PagedResponse, PostView, PublicProfileView, UserProfile, UserStats } from '../types';

/**
 * API 版本前缀。
 *
 * 走 /api/v1 而不是 /api：/api 是 v1 之前的旧契约（列表直接返回全量数组、
 * 无分页元数据），服务端会保留一段时间兼容，但新代码一律用 /api/v1，
 * 见 docs/MOBILE-API-PLAN.md 4.1「只做加法」的演进规则。
 *
 * 服务端有一个 ApiVersionPrefixFilter 把 /api/v1/** 改写成 /api/**，
 * 所以 SecurityConfig 的白名单只需维护一份；v1 独有端点（如 /posts/page）
 * 已单独加白，不会被改写。
 */
export const API_PREFIX = '/api/v1';

// 登录态在 httpOnly Cookie 里，前端拿不到也存不了 token。
// withCredentials 让浏览器在跨端口开发（5173 -> 8080）时也带上 Cookie。
const api = axios.create({ baseURL: API_PREFIX, withCredentials: true });
const publicApi = axios.create({ baseURL: API_PREFIX, withCredentials: true });
// 仅 DEV 生效：vite 代理不可用时直连后端，避免把跨域问题当成接口失败
const localApi = axios.create({ baseURL: 'http://127.0.0.1:8080/api/v1', withCredentials: true });

/** 401 时广播给 auth store；由它决定是否把本地登录态降级为访客。 */
export const UNAUTHORIZED_EVENT = 'unipo:unauthorized';

/** Cookie 里只有 csrf token 这一项是故意让 JS 读的（双提交防护）。 */
export function csrfToken(): string {
  const prefix = 'unipo_csrf=';
  for (const part of document.cookie.split(';')) {
    const item = part.trim();
    if (item.startsWith(prefix)) {
      return decodeURIComponent(item.slice(prefix.length));
    }
  }
  return '';
}

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE']);

/**
 * Cookie 认证的前端侧配套：
 * - 写请求自动带 X-CSRF-Token（服务端 CsrfFilter 校验）；
 * - 401 时广播事件，由 auth store 统一降级，不在这里强行跳转/刷新。
 *
 * 不再有「单飞刷新」：会话在服务端滑动续期，浏览器每次请求自动带 Cookie，
 * 前端不需要知道 access/refresh token 的存在。
 */
function attachSession(client: AxiosInstance) {
  client.interceptors.request.use((config) => {
    const method = (config.method || 'get').toUpperCase();
    if (!SAFE_METHODS.has(method)) {
      const token = csrfToken();
      if (token) {
        config.headers['X-CSRF-Token'] = token;
      }
    }
    return config;
  });
  client.interceptors.response.use(
    (response) => response,
    (error) => {
      if (axios.isAxiosError(error) && error.response?.status === 401) {
        const url = error.config?.url || '';
        // 登录/注册自己的 401 是「密码错」，不是会话失效，别触发全局降级。
        if (!url.includes('/auth/login') && !url.includes('/auth/register')) {
          window.dispatchEvent(new Event(UNAUTHORIZED_EVENT));
        }
      }
      return Promise.reject(error);
    }
  );
}

attachSession(api);
attachSession(publicApi);
attachSession(localApi);

/**
 * 剥掉响应包取业务数据。
 *
 * 后端返回 {"code":"OK","message":"","data":...,"requestId":"..."}，
 * 这里只取 data；code 在成功路径上恒为 OK，不需要判断。
 */
async function unwrap<T>(request: Promise<{ data: ApiResponse<T> }>) {
  const response = await request;
  return response.data.data;
}

/** 取错误包里的业务错误码（App 端 switch 这个，不要匹配中文文案）。 */
export function apiErrorCode(error: unknown): string | null {
  if (!axios.isAxiosError(error)) return null;
  const body = error.response?.data as Partial<ApiErrorBody> | undefined;
  return body?.code || null;
}

/** 取错误请求的 requestId，方便用户报障时对日志。 */
export function apiErrorRequestId(error: unknown): string | null {
  if (!axios.isAxiosError(error)) return null;
  const body = error.response?.data as Partial<ApiErrorBody> | undefined;
  return body?.requestId || error.response?.headers?.['x-request-id'] || null;
}

export function apiErrorMessage(error: unknown, fallback = '请求失败，请稍后重试') {
  if (!axios.isAxiosError(error)) {
    return error instanceof Error ? error.message : fallback;
  }
  const body = error.response?.data as Partial<ApiErrorBody> | undefined;
  return body?.message || error.message || fallback;
}

export function isAuthError(error: unknown) {
  return axios.isAxiosError(error) && error.response?.status === 401;
}

/**
 * 登录专用：把 axios 错误转成带 code 的 Error，供视图层区分
 * 「密码错」和「网络断了」——现在两者都只是 message 不一样，
 * 而 message 是用来展示的，不该拿来做分支。
 */
async function unwrapLogin(request: Promise<{ data: ApiResponse<AuthSessionView> }>) {
  try {
    return await unwrap(request);
  } catch (error) {
    const code = apiErrorCode(error);
    const message = apiErrorMessage(error, '登录失败');
    const wrapped = new Error(message) as Error & { code?: string | null };
    wrapped.code = code;
    throw wrapped;
  }
}

async function loginWithFallback(email: string, password: string) {
  try {
    return await unwrapLogin(api.post('/auth/login', { email, password }));
  } catch (primaryError) {
    const shouldTryLocalApi = import.meta.env.DEV && axios.isAxiosError(primaryError) && !primaryError.response;
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
   * 游标分页列表（推荐用法）。
   *
   * limit 默认 20、上限 50；cursor 传上一页返回的 nextCursor。
   * 首页首屏用 postsPage() 拿第一页，滚动到底再用同一 cursor 续拉，
   * 不要自己拼 page 参数——服务端只支持游标。
   */
  postsPage: (tab = 'recommend', cursor?: string | null, limit = 20) =>
    unwrap<PagedResponse<PostView>>(api.get('/posts/page', {
      params: { tab, ...(cursor ? { cursor } : {}), limit }
    })),
  boards: () => unwrap<BoardView[]>(api.get('/posts/boards')),
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
