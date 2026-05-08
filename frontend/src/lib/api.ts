import axios from 'axios';
import type { AxiosInstance } from 'axios';
import type { ApiResponse, BoardView, CheckInView, CommentView, ConversationView, InteractionNoticeView, MessageView, PostView, PublicProfileView, UserProfile, UserStats } from '../types';

const api = axios.create({ baseURL: '/api' });
const publicApi = axios.create({ baseURL: '/api' });
const localApi = axios.create({ baseURL: 'http://127.0.0.1:8080/api' });
const TOKEN_KEY = 'bcg_token';

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

export function saveAuthToken(token: string) {
  if (!isUsableToken(token)) {
    localStorage.removeItem(TOKEN_KEY);
    throw new Error('登录返回的 token 无效，请重新登录');
  }
  localStorage.setItem(TOKEN_KEY, token.trim());
}

export function clearAuthToken() {
  localStorage.removeItem(TOKEN_KEY);
}

function attachToken(client: AxiosInstance) {
  client.interceptors.request.use((config) => {
    const token = authToken();
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  });
}

attachToken(api);
attachToken(localApi);

async function unwrap<T>(request: Promise<{ data: ApiResponse<T> }>) {
  const response = await request;
  return response.data.data;
}

export function apiErrorMessage(error: unknown, fallback = '请求失败，请稍后重试') {
  return axios.isAxiosError(error)
    ? error.response?.data?.message || error.message || fallback
    : error instanceof Error
      ? error.message
      : fallback;
}

export function isAuthError(error: unknown) {
  return axios.isAxiosError(error) && error.response?.status === 401;
}

async function unwrapLogin(request: Promise<{ data: ApiResponse<{ token: string; user: UserProfile }> }>) {
  try {
    return await unwrap(request);
  } catch (error) {
    const message = axios.isAxiosError(error)
      ? error.response?.data?.message || error.message
      : error instanceof Error
        ? error.message
        : '登录失败';
    throw new Error(message);
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
  posts: (tab = 'recommend') => unwrap<PostView[]>(api.get('/posts', { params: { tab } })),
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
  logout: () => unwrap<boolean>(api.post('/auth/logout')),
  heartbeat: () => unwrap<boolean>(api.post('/auth/heartbeat')),
  register: (payload: { email: string; password: string; confirmPassword: string; nickname: string; verificationCode: string }) =>
    unwrap<{ token: string; user: UserProfile }>(publicApi.post('/auth/register', payload)),
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
