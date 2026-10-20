import axios from 'axios';
import type { AxiosInstance } from 'axios';
import type { ApiErrorBody, ApiResponse } from '../types';

/**
 * HTTP 基础设施：axios 实例、Cookie 会话、CSRF、错误包解析。
 *
 * 这里只放「所有请求都一样」的东西；具体端点按领域放在同目录其他文件
 * （auth / posts / users / messages / media），新增接口时只动对应文件。
 *
 * 认证模型（ADR-0001 D3）：Web 用 httpOnly Cookie，前端拿不到 token。
 * - 每个请求自动带 Cookie（withCredentials，跨端口开发也需要）；
 * - 写请求自动带 X-CSRF-Token（值来自非 HttpOnly 的 unipo_csrf）；
 * - 401 广播事件，由 auth store 降级，不在这一层做刷新/跳转。
 */

/** API 版本前缀。新代码一律 /api/v1；/api 是旧契约，只保留兼容。 */
export const API_PREFIX = '/api/v1';

export const api = axios.create({ baseURL: API_PREFIX, withCredentials: true });
export const publicApi = axios.create({ baseURL: API_PREFIX, withCredentials: true });
// 仅 DEV 生效：vite 代理不可用时直连后端，避免把跨域问题当成接口失败
export const localApi = axios.create({ baseURL: 'http://127.0.0.1:8080/api/v1', withCredentials: true });

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

/** 剥掉统一响应包，只取 data。 */
export async function unwrap<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
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
