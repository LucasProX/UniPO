import dayjs from 'dayjs';
import type { BoardCode, BoardView, CampusPost, PostView } from '../types';

/**
 * 帖子相关的纯函数集合。这些函数被 App.vue 和各个 Post 组件共用，
 * 搬出 App.vue 是为了让 App 端（WebView / 原生）也能直接复用。
 * 注意：这里不能出现任何 Vue 响应式依赖（ref/computed 都不行）。
 */

export const COVER_FALLBACKS = [
  'https://images.unsplash.com/photo-1521587760476-6c12a4b040da?auto=format&fit=crop&w=900&q=80',
  'https://images.unsplash.com/photo-1434030216411-0b793f4b4173?auto=format&fit=crop&w=900&q=80',
  'https://images.unsplash.com/photo-1497366754035-f200968a6e72?auto=format&fit=crop&w=900&q=80',
  'https://images.unsplash.com/photo-1515879218367-8466d910aaa4?auto=format&fit=crop&w=900&q=80'
];

export const LEVEL_TIERS = [
  '星途引路人',
  '食域裁决官',
  '万社登临者',
  '书渊夜帝君',
  '课业天机主',
  '实训执掌者',
  '百味揽星客',
  '绩点造化尊',
  '学海无双尊',
  '校园万古名'
];

export function fallbackAvatar(name: string) {
  return `https://api.dicebear.com/8.x/initials/svg?seed=${encodeURIComponent(name || 'PO')}`;
}

export function fallbackCover(id: number) {
  return COVER_FALLBACKS[Math.abs(id) % COVER_FALLBACKS.length];
}

export function boardLabel(board: BoardCode, boards: BoardView[]) {
  return boards.find((item) => item.code === board)?.name || '推荐';
}

export function roleLabel(post: CampusPost | PostView) {
  if (post.author.role === 'SCHOOL_OPERATOR') return '校霸';
  if (post.author.role === 'COLLEGE_OPERATOR') return '院花';
  if (post.author.role === 'MAJOR_OPERATOR') return '级草';
  if (post.author.role === 'ADMIN') return '管理员';
  return post.official ? '官方 PO' : '同学';
}

export function formatPublishedAt(value: string) {
  const date = dayjs(value);
  return date.year() === dayjs().year() ? date.format('MM/DD HH:mm') : date.format('YYYY/MM/DD HH:mm');
}

export function compactPostTitle(title: string) {
  const compact = (title || '').replace(/\s+/g, '');
  return compact.length > 16 ? compact.slice(0, 16) : compact;
}

export function levelTier(level: number) {
  return Math.min(10, Math.max(1, Math.ceil(level / 10)));
}

export function levelTitleFor(level: number) {
  return LEVEL_TIERS[levelTier(level) - 1];
}

export function plainPostContent(content: string) {
  const htmlAsText = content
    .replace(/<img[^>]*>/gi, '')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(div|p|li|ul|ol|h[1-6])>/gi, '\n')
    .replace(/<[^>]+>/g, '');
  const textarea = document.createElement('textarea');
  textarea.innerHTML = htmlAsText;
  return textarea.value
    .replace(/!\[[^\]]*]\([^)]+\)/g, '')
    .replace(/\*\*(.*?)\*\*/g, '$1')
    .replace(/\[size=(large|small)](.*?)\[\/size]/g, '$2')
    .replace(/\[color=#[0-9a-fA-F]{3,8}](.*?)\[\/color]/g, '$1')
    .trim();
}

export function recommendScore(post: CampusPost | PostView) {
  const hours = Math.max(1, dayjs().diff(dayjs(post.publishedAt), 'hour', true));
  const officialBoost = post.official ? 60 : 0;
  const pinnedBoost = post.pinned ? 80 : 0;
  return post.likeCount * 1.2 + post.commentCount * 4 + post.favoriteCount * 1.5 + post.shareCount * 2 + officialBoost + pinnedBoost - hours * 6;
}

export function hotScore(post: CampusPost) {
  return (post.last2hLikes || Math.round(post.likeCount * 0.38)) + (post.last2hComments || Math.round(post.commentCount * 0.48)) * 4 + post.shareCount * 0.5;
}