import { api, unwrap } from './http';
import type { PostView, SpotlightView } from '../types';

/**
 * 管理后台 API。全部挂在 /api/admin/**，权限由后端 hasAnyRole 拦截。
 * 前端这里只做类型和路径映射，不做权限判断（菜单可见性也不等于授权）。
 */

export interface AdminOverview {
  users: number;
  posts: number;
  publishedPosts: number;
  comments: number;
}

export interface AdminUser {
  id: number;
  publicUid: string;
  email: string;
  nickname: string;
  role: string;
  status: string;
  operatorScope?: string | null;
  createdAt: string;
}

export interface AuditLog {
  id: number;
  actorId: number | null;
  actorEmail: string | null;
  action: string;
  targetType: string | null;
  targetId: string | null;
  detail: string | null;
  requestId: string | null;
  createdAt: string;
}

export interface ReportPoint {
  day: string;
  newUsers: number;
  publishedPosts: number;
}

export interface AdminReport {
  days: number;
  points: ReportPoint[];
  newUsersTotal: number;
  postsTotal: number;
}

export interface MonitorSummary {
  status: string;
  uptimeSeconds: number;
  heapUsedMb: number;
  heapMaxMb: number;
  realtimeConnections: number;
  eventsPublished: number;
  eventsDelivered: number;
  rateLimitBlocked: number;
  sampledAt: string;
}

export interface SpotlightUpsert {
  title: string;
  subtitle?: string | null;
  badge?: string | null;
  imageUrl?: string | null;
  linkUrl?: string | null;
  source?: 'MANUAL' | 'ALGORITHM';
  referencePostId?: number | null;
  weight?: number;
  enabled?: boolean;
  startsAt?: string | null;
  endsAt?: string | null;
}

export const adminApi = {
  overview: () => unwrap<AdminOverview>(api.get('/admin/analytics/overview')),

  users: () => unwrap<AdminUser[]>(api.get('/admin/users')),
  updateUserStatus: (id: number, status: string) =>
    unwrap<AdminUser>(api.put(`/admin/users/${id}/status`, { status })),
  updateUserRole: (id: number, role: string, operatorScope?: string | null) =>
    unwrap<AdminUser>(api.put(`/admin/users/${id}/role`, { role, operatorScope: operatorScope ?? null })),

  posts: (keyword?: string, status?: string, limit = 50) =>
    unwrap<PostView[]>(api.get('/admin/posts', {
      params: { ...(keyword ? { keyword } : {}), ...(status ? { status } : {}), limit }
    })),
  moderatePost: (id: number, patch: { pinned?: boolean; official?: boolean; dontMiss?: boolean; status?: string }) =>
    unwrap<PostView>(api.patch(`/admin/posts/${id}`, patch)),

  spotlights: () => unwrap<SpotlightView[]>(api.get('/admin/spotlights')),
  createSpotlight: (payload: SpotlightUpsert) => unwrap<SpotlightView>(api.post('/admin/spotlights', payload)),
  updateSpotlight: (id: number, payload: SpotlightUpsert) =>
    unwrap<SpotlightView>(api.put(`/admin/spotlights/${id}`, payload)),
  deleteSpotlight: (id: number) => unwrap<boolean>(api.delete(`/admin/spotlights/${id}`)),

  report: (days = 14) => unwrap<AdminReport>(api.get('/admin/reports/daily', { params: { days } })),
  monitor: () => unwrap<MonitorSummary>(api.get('/admin/monitor/summary')),
  auditLogs: (limit = 50) => unwrap<AuditLog[]>(api.get('/admin/audit-logs', { params: { limit } }))
};
