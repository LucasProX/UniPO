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
const API_PREFIX = '/api/v1';

const api = axios.create({ baseURL: API_PREFIX });
const publicApi = axios.create({ baseURL: API_PREFIX });
// 仅 DEV 生效：vite 代理不可用时直连后端，避免把跨域问题当成接口失败
const localApi = axios.create({ baseURL: 'http://127.0.0.1:8080/api/v1' });
const TOKEN_KEY = 'bcg_token';
/** refresh token：只用来换新的 access token，不参与业务请求头。 */
const REFRESH_TOKEN_KEY = 'bcg_refresh_token';

function isUsableToken(token: string | null | undefined) {
  if (!token) return false;
  const value = token.trim();
  return value !== 'undefined' && value !== 'null' && value.split('.').length === 3;
}

export function authToken() {
  const token = localStorage.getItem(TOKEN_KEY);
  if (!isUsableToken(token)) {
    localStorage.removeItem(TOKEN_KEY);
    return '';
  }
  return token!.trim();
}

export function hasAuthToken() {
  return Boolean(authToken());
}

export function saveAuthToken(token: string, refreshToken?: string | null) {
  if (!isUsableToken(token)) {
    localStorage.removeItem(TOKEN_KEY);
    throw new Error('登录返回的 token 无效，请重新登录');
  }
  localStorage.setItem(TOKEN_KEY, token.trim());
  if (refreshToken && isUsableToken(refreshToken)) {
    localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken.trim());
  } else {
    localStorage.removeItem(REFRESH_TOKEN_KEY);
  }
}

export function authRefreshToken() {
  const token = localStorage.getItem(REFRESH_TOKEN_KEY);
  if (!isUsableToken(token)) {
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    return '';
  }
  return token!.trim();
}

export function clearAuthToken() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
}

/**
 * 单飞刷新：同一时刻只发一个 /auth/refresh。
 *
 * 首屏经常同时打七八个接口，access token 一过期它们会一起 401；
 * 各自去刷新的话服务端会收到一串重复请求（还都带着同一个 refresh token）。
 * 这里用模块级 Promise 合并：谁先到谁发，后来的等同一个结果。
 */
let refreshing: Promise<boolean> | null = null;

async function refreshAccessToken(): Promise<boolean> {
  const refreshToken = authRefreshToken();
  if (!refreshToken) return false;
  if (!refreshing) {
    refreshing = publicApi
      .post('/auth/refresh', { refreshToken })
      .then((response) => {
        const data = response.data?.data as { token?: string; refreshToken?: string } | undefined;
        if (!data?.token) return false;
        saveAuthToken(data.token, data.refreshToken || refreshToken);
        return true;
      })
      .catch(() => false)
      .finally(() => {
        refreshing = null;
      });
  }
  return refreshing;
}

type RetryableConfig = { _retriedAuth?: boolean; url?: string; headers?: Record<string, unknown> };

function attachToken(client: AxiosInstance) {
  client.interceptors.request.use((config) => {
    const token = authToken();
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  });
  client.interceptors.response.use(
    (response) => response,
    async (error) => {
      if (!axios.isAxiosError(error) || error.response?.status !== 401) {
        return Promise.reject(error);
      }
      const config = error.config as (typeof error.config & RetryableConfig) | undefined;
      const url = config?.url || '';
      const worthRetrying = Boolean(config) && !config!._retriedAuth
        && !url.includes('/auth/refresh')
        && !url.includes('/auth/login')
        && !url.includes('/auth/logout');
      if (worthRetrying && (await refreshAccessToken())) {
        config!._retriedAuth = true;
        config!.headers = config!.headers || {};
        (config!.headers as Record<string, unknown>).Authorization = `Bearer ${authToken()}`;
        return client.request(config!);
      }
      clearAuthToken();
      return Promise.reject(error);
    }
  );
}

attachToken(api);
attachToken(localApi);

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
  logout: (refreshToken?: string) => unwrap<boolean>(api.post('/auth/logout', refreshToken ? { refreshToken } : {})),
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
