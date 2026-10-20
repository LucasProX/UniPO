<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { RouterLink } from 'vue-router';
import { ArrowUpRight, BarChart3, FileText, Image, MessageSquare, Users } from 'lucide-vue-next';
import { adminApi, type AdminOverview, type AdminReport, type AuditLog } from '../../lib/adminApi';

const overview = ref<AdminOverview | null>(null);
const report = ref<AdminReport | null>(null);
const audit = ref<AuditLog[]>([]);
const loading = ref(true);
const error = ref('');

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const [o, r, a] = await Promise.all([adminApi.overview(), adminApi.report(7), adminApi.auditLogs(8)]);
    overview.value = o;
    report.value = r;
    audit.value = a;
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

onMounted(load);

const maxDaily = computed(() => Math.max(1, ...(report.value?.points.map((p) => Math.max(p.newUsers, p.publishedPosts)) ?? [1])));

const cards = computed(() => [
  { label: '用户总数', value: overview.value?.users ?? 0, icon: Users, tone: 'from-blue-500 to-indigo-500' },
  { label: '帖子总数', value: overview.value?.posts ?? 0, icon: FileText, tone: 'from-violet-500 to-fuchsia-500' },
  { label: '已发布', value: overview.value?.publishedPosts ?? 0, icon: BarChart3, tone: 'from-emerald-500 to-teal-500' },
  { label: '评论数', value: overview.value?.comments ?? 0, icon: MessageSquare, tone: 'from-amber-500 to-orange-500' }
]);

function actionLabel(action: string) {
  return action.replace('.', ' · ');
}
</script>

<template>
  <div class="space-y-6">
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h2 class="text-xl font-semibold">概览</h2>
        <p class="mt-1 text-sm text-slate-500">平台今天的状态、最近的操作，以及需要你处理的事。</p>
      </div>
      <button class="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs text-slate-600 hover:bg-slate-50" @click="load">
        刷新数据
      </button>
    </div>

    <div v-if="error" class="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{{ error }}</div>

    <div class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <div v-for="card in cards" :key="card.label" class="rounded-2xl border border-slate-200/70 bg-white p-5 shadow-sm">
        <div class="flex items-start justify-between">
          <div>
            <div class="text-xs text-slate-500">{{ card.label }}</div>
            <div class="mt-2 text-2xl font-semibold tracking-tight">{{ loading ? '—' : card.value }}</div>
          </div>
          <span class="grid h-10 w-10 place-items-center rounded-xl bg-gradient-to-br text-white" :class="card.tone">
            <component :is="card.icon" :size="18" />
          </span>
        </div>
      </div>
    </div>

    <div class="grid gap-4 lg:grid-cols-[1.4fr_1fr]">
      <section class="rounded-2xl border border-slate-200/70 bg-white p-5 shadow-sm">
        <div class="flex items-center justify-between">
          <h3 class="text-sm font-semibold">近 7 天新增</h3>
          <span class="text-xs text-slate-400">
            用户 {{ report?.newUsersTotal ?? 0 }} · 帖子 {{ report?.postsTotal ?? 0 }}
          </span>
        </div>
        <div v-if="report?.points.length" class="mt-5 flex h-40 items-end gap-2">
          <div v-for="point in report.points" :key="point.day" class="flex flex-1 flex-col items-center gap-2">
            <div class="flex h-32 w-full items-end justify-center gap-1">
              <div class="w-2.5 rounded-t bg-blue-500/80" :style="{ height: `${(point.newUsers / maxDaily) * 100}%` }" :title="`用户 ${point.newUsers}`" />
              <div class="w-2.5 rounded-t bg-violet-400/80" :style="{ height: `${(point.publishedPosts / maxDaily) * 100}%` }" :title="`帖子 ${point.publishedPosts}`" />
            </div>
            <span class="text-[10px] text-slate-400">{{ point.day.slice(5) }}</span>
          </div>
        </div>
        <div v-else class="mt-5 grid h-40 place-items-center text-sm text-slate-400">暂无数据</div>
        <div class="mt-4 flex gap-4 text-xs text-slate-500">
          <span class="flex items-center gap-1.5"><i class="h-2 w-2 rounded-full bg-blue-500" />新增用户</span>
          <span class="flex items-center gap-1.5"><i class="h-2 w-2 rounded-full bg-violet-400" />发布帖子</span>
        </div>
      </section>

      <section class="space-y-4">
        <div class="rounded-2xl border border-slate-200/70 bg-white p-5 shadow-sm">
          <h3 class="text-sm font-semibold">快捷操作</h3>
          <div class="mt-3 grid gap-2">
            <RouterLink to="/admin/posts" class="flex items-center justify-between rounded-xl border border-slate-200 px-3 py-2.5 text-sm hover:border-blue-300 hover:bg-blue-50/50">
              <span class="flex items-center gap-2"><FileText :size="16" class="text-blue-500" />内容审核</span>
              <ArrowUpRight :size="14" class="text-slate-400" />
            </RouterLink>
            <RouterLink to="/admin/spotlights" class="flex items-center justify-between rounded-xl border border-slate-200 px-3 py-2.5 text-sm hover:border-blue-300 hover:bg-blue-50/50">
              <span class="flex items-center gap-2"><Image :size="16" class="text-violet-500" />轮播运营</span>
              <ArrowUpRight :size="14" class="text-slate-400" />
            </RouterLink>
            <RouterLink to="/admin/monitor" class="flex items-center justify-between rounded-xl border border-slate-200 px-3 py-2.5 text-sm hover:border-blue-300 hover:bg-blue-50/50">
              <span class="flex items-center gap-2"><BarChart3 :size="16" class="text-emerald-500" />实时监控</span>
              <ArrowUpRight :size="14" class="text-slate-400" />
            </RouterLink>
          </div>
        </div>
      </section>
    </div>

    <section class="rounded-2xl border border-slate-200/70 bg-white p-5 shadow-sm">
      <div class="flex items-center justify-between">
        <h3 class="text-sm font-semibold">最近操作</h3>
        <RouterLink to="/admin/users" class="text-xs text-blue-600 hover:underline">用户与权限 →</RouterLink>
      </div>
      <div v-if="audit.length" class="mt-4 overflow-x-auto">
        <table class="w-full text-left text-sm">
          <thead class="text-xs text-slate-400">
            <tr>
              <th class="pb-2 font-medium">时间</th>
              <th class="pb-2 font-medium">操作者</th>
              <th class="pb-2 font-medium">动作</th>
              <th class="pb-2 font-medium">对象</th>
              <th class="pb-2 font-medium">详情</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100">
            <tr v-for="log in audit" :key="log.id">
              <td class="py-2.5 text-xs text-slate-500">{{ new Date(log.createdAt).toLocaleString() }}</td>
              <td class="py-2.5 text-xs">{{ log.actorEmail }}</td>
              <td class="py-2.5"><span class="rounded-md bg-slate-100 px-2 py-0.5 font-mono text-[11px]">{{ actionLabel(log.action) }}</span></td>
              <td class="py-2.5 text-xs text-slate-600">{{ log.targetType }}#{{ log.targetId }}</td>
              <td class="max-w-[280px] truncate py-2.5 text-xs text-slate-500">{{ log.detail }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="mt-4 rounded-xl border border-dashed border-slate-200 py-8 text-center text-sm text-slate-400">
        还没有后台操作记录。封禁用户、审核帖子、改轮播位都会记录在这里。
      </div>
    </section>
  </div>
</template>
