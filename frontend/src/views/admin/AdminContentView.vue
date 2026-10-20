<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { Pin, ShieldCheck, Sparkles, Trash2, Undo2 } from 'lucide-vue-next';
import { adminApi } from '../../lib/adminApi';
import type { PostView } from '../../types';

const posts = ref<PostView[]>([]);
const keyword = ref('');
const status = ref('');
const loading = ref(true);
const error = ref('');
const busyId = ref<number | null>(null);

async function load() {
  loading.value = true;
  error.value = '';
  try {
    posts.value = await adminApi.posts(keyword.value.trim() || undefined, status.value || undefined, 50);
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

async function moderate(post: PostView, patch: { pinned?: boolean; official?: boolean; dontMiss?: boolean; status?: string }) {
  if (patch.status === 'removed' && !window.confirm(`确定下架「${post.title}」吗？`)) return;
  busyId.value = post.id;
  try {
    const updated = await adminApi.moderatePost(post.id, patch);
    posts.value = posts.value.map((item) => (item.id === updated.id ? updated : item));
  } catch (e) {
    error.value = e instanceof Error ? e.message : '操作失败';
  } finally {
    busyId.value = null;
  }
}

function statusLabel(value: string) {
  return value === 'published' ? '已发布' : value === 'removed' ? '已下架' : value === 'draft' ? '草稿' : value;
}

onMounted(load);
</script>

<template>
  <div class="space-y-5">
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h2 class="text-xl font-semibold">内容审核</h2>
        <p class="mt-1 text-sm text-slate-500">置顶、加精、设为免错过，或下架违规内容。所有操作都会写审计。</p>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <input v-model="keyword" placeholder="搜索标题或正文" class="w-56 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm outline-none focus:border-blue-400" @keyup.enter="load" />
        <select v-model="status" class="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm" @change="load">
          <option value="">全部状态</option>
          <option value="published">已发布</option>
          <option value="removed">已下架</option>
          <option value="draft">草稿</option>
        </select>
        <button class="rounded-lg bg-slate-900 px-3 py-1.5 text-sm text-white hover:bg-slate-800" @click="load">查询</button>
      </div>
    </div>

    <div v-if="error" class="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{{ error }}</div>

    <div class="overflow-hidden rounded-2xl border border-slate-200/70 bg-white shadow-sm">
      <table class="w-full text-left text-sm">
        <thead class="border-b border-slate-100 bg-slate-50/70 text-xs text-slate-500">
          <tr>
            <th class="px-4 py-3 font-medium">帖子</th>
            <th class="px-3 py-3 font-medium">作者</th>
            <th class="px-3 py-3 font-medium">状态</th>
            <th class="px-3 py-3 font-medium">互动</th>
            <th class="px-4 py-3 text-right font-medium">操作</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-100">
          <tr v-if="loading"><td colspan="5" class="px-4 py-10 text-center text-sm text-slate-400">加载中…</td></tr>
          <tr v-else-if="!posts.length"><td colspan="5" class="px-4 py-10 text-center text-sm text-slate-400">没有符合条件的帖子</td></tr>
          <tr v-for="post in posts" :key="post.id" class="align-top hover:bg-slate-50/60">
            <td class="max-w-[420px] px-4 py-3">
              <div class="flex items-center gap-2">
                <span class="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] text-slate-500">{{ post.board }}</span>
                <span v-if="post.pinned" class="rounded bg-rose-50 px-1.5 py-0.5 text-[10px] text-rose-600">置顶</span>
                <span v-if="post.official" class="rounded bg-blue-50 px-1.5 py-0.5 text-[10px] text-blue-600">官方</span>
                <span v-if="post.dontMiss" class="rounded bg-amber-50 px-1.5 py-0.5 text-[10px] text-amber-600">免错过</span>
              </div>
              <div class="mt-1 truncate font-medium text-slate-900">{{ post.title }}</div>
              <div class="mt-0.5 line-clamp-1 text-xs text-slate-400">{{ post.excerpt || post.content }}</div>
            </td>
            <td class="px-3 py-3 text-xs text-slate-600">{{ post.author?.nickname || post.author?.uid || '—' }}</td>
            <td class="px-3 py-3">
              <span class="rounded-full px-2 py-0.5 text-xs" :class="post.status === 'published' ? 'bg-emerald-50 text-emerald-700' : post.status === 'removed' ? 'bg-rose-50 text-rose-600' : 'bg-slate-100 text-slate-600'">
                {{ statusLabel(post.status) }}
              </span>
            </td>
            <td class="px-3 py-3 text-xs text-slate-500">赞 {{ post.likeCount }} · 评 {{ post.commentCount }}<br />藏 {{ post.favoriteCount }} · 转 {{ post.shareCount }}</td>
            <td class="px-4 py-3">
              <div class="flex flex-wrap justify-end gap-1.5">
                <button class="admin-icon-btn" :class="{ 'admin-icon-btn--on': post.pinned }" title="置顶" :disabled="busyId === post.id" @click="moderate(post, { pinned: !post.pinned })"><Pin :size="14" /></button>
                <button class="admin-icon-btn" :class="{ 'admin-icon-btn--on': post.official }" title="官方" :disabled="busyId === post.id" @click="moderate(post, { official: !post.official })"><ShieldCheck :size="14" /></button>
                <button class="admin-icon-btn" :class="{ 'admin-icon-btn--on': post.dontMiss }" title="免错过" :disabled="busyId === post.id" @click="moderate(post, { dontMiss: !post.dontMiss })"><Sparkles :size="14" /></button>
                <button v-if="post.status !== 'removed'" class="admin-icon-btn admin-icon-btn--danger" title="下架" :disabled="busyId === post.id" @click="moderate(post, { status: 'removed' })"><Trash2 :size="14" /></button>
                <button v-else class="admin-icon-btn" title="恢复" :disabled="busyId === post.id" @click="moderate(post, { status: 'published' })"><Undo2 :size="14" /></button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
