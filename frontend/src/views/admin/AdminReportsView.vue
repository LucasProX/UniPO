<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { adminApi, type AdminReport } from '../../lib/adminApi';

const days = ref(14);
const report = ref<AdminReport | null>(null);
const loading = ref(true);
const error = ref('');

async function load() {
  loading.value = true;
  error.value = '';
  try {
    report.value = await adminApi.report(days.value);
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

onMounted(load);

const maxValue = computed(() => Math.max(1, ...(report.value?.points.map((p) => Math.max(p.newUsers, p.publishedPosts)) ?? [1])));

function barHeight(value: number) {
  return `${Math.max(2, (value / maxValue.value) * 100)}%`;
}
</script>

<template>
  <div class="space-y-5">
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h2 class="text-xl font-semibold">报表</h2>
        <p class="mt-1 text-sm text-slate-500">新增用户与发布帖子的按日走势。目前是聚合查询，后续可以接埋点做留存/活跃。</p>
      </div>
      <div class="flex items-center gap-2">
        <select v-model.number="days" class="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm" @change="load">
          <option :value="7">近 7 天</option>
          <option :value="14">近 14 天</option>
          <option :value="30">近 30 天</option>
        </select>
        <button class="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-600" @click="load">刷新</button>
      </div>
    </div>

    <div v-if="error" class="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{{ error }}</div>

    <div class="grid gap-4 sm:grid-cols-2">
      <div class="rounded-2xl border border-slate-200/70 bg-white p-5 shadow-sm">
        <div class="text-xs text-slate-500">新增用户（合计）</div>
        <div class="mt-1 text-2xl font-semibold">{{ report?.newUsersTotal ?? '—' }}</div>
      </div>
      <div class="rounded-2xl border border-slate-200/70 bg-white p-5 shadow-sm">
        <div class="text-xs text-slate-500">发布帖子（合计）</div>
        <div class="mt-1 text-2xl font-semibold">{{ report?.postsTotal ?? '—' }}</div>
      </div>
    </div>

    <section class="rounded-2xl border border-slate-200/70 bg-white p-5 shadow-sm">
      <div class="flex items-center justify-between">
        <h3 class="text-sm font-semibold">按日走势</h3>
        <div class="flex gap-4 text-xs text-slate-500">
          <span class="flex items-center gap-1.5"><i class="h-2 w-2 rounded-full bg-blue-500" />新增用户</span>
          <span class="flex items-center gap-1.5"><i class="h-2 w-2 rounded-full bg-violet-400" />发布帖子</span>
        </div>
      </div>
      <div v-if="report?.points.length" class="mt-6 flex h-56 items-end gap-1.5 overflow-x-auto">
        <div v-for="point in report.points" :key="point.day" class="flex min-w-[28px] flex-1 flex-col items-center gap-1">
          <div class="flex h-44 w-full items-end justify-center gap-0.5">
            <div class="w-2 rounded-t bg-blue-500/85" :style="{ height: barHeight(point.newUsers) }" :title="`${point.day} 用户 ${point.newUsers}`" />
            <div class="w-2 rounded-t bg-violet-400/85" :style="{ height: barHeight(point.publishedPosts) }" :title="`${point.day} 帖子 ${point.publishedPosts}`" />
          </div>
          <span class="text-[10px] text-slate-400">{{ point.day.slice(5) }}</span>
        </div>
      </div>
      <div v-else class="mt-6 grid h-56 place-items-center text-sm text-slate-400">暂无数据</div>
    </section>

    <section class="overflow-hidden rounded-2xl border border-slate-200/70 bg-white shadow-sm">
      <table class="w-full text-left text-sm">
        <thead class="border-b border-slate-100 bg-slate-50/70 text-xs text-slate-500">
          <tr><th class="px-4 py-3 font-medium">日期</th><th class="px-4 py-3 font-medium">新增用户</th><th class="px-4 py-3 font-medium">发布帖子</th></tr>
        </thead>
        <tbody class="divide-y divide-slate-100">
          <tr v-for="point in (report?.points ?? []).slice().reverse()" :key="point.day">
            <td class="px-4 py-2.5 text-xs text-slate-600">{{ point.day }}</td>
            <td class="px-4 py-2.5">{{ point.newUsers }}</td>
            <td class="px-4 py-2.5">{{ point.publishedPosts }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>
