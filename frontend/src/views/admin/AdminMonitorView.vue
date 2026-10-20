<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { Activity, Cpu, Radio, Timer, Zap } from 'lucide-vue-next';
import { adminApi, type MonitorSummary } from '../../lib/adminApi';

const summary = ref<MonitorSummary | null>(null);
const loading = ref(true);
const error = ref('');
const autoRefresh = ref(true);
let timer: number | undefined;

async function load() {
  loading.value = true;
  error.value = '';
  try {
    summary.value = await adminApi.monitor();
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function start() {
  stop();
  timer = window.setInterval(() => { void load(); }, 10000);
}

function stop() {
  if (timer) window.clearInterval(timer);
  timer = undefined;
}

function toggleAuto() {
  autoRefresh.value = !autoRefresh.value;
  if (autoRefresh.value) start();
  else stop();
}

function formatUptime(seconds: number) {
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  return d > 0 ? `${d} 天 ${h} 小时` : `${h} 小时 ${m} 分`;
}

const heapPercent = computed(() => {
  if (!summary.value || !summary.value.heapMaxMb) return 0;
  return Math.min(100, Math.round((summary.value.heapUsedMb / summary.value.heapMaxMb) * 100));
});

const healthy = computed(() => summary.value?.status === 'UP');

onMounted(async () => { await load(); start(); });
onUnmounted(stop);
</script>

<template>
  <div class="space-y-5">
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h2 class="text-xl font-semibold">监控</h2>
        <p class="mt-1 text-sm text-slate-500">健康状态与关键指标。Prometheus 抓取地址：<code class="rounded bg-slate-100 px-1">/actuator/prometheus</code>。</p>
      </div>
      <div class="flex items-center gap-2 text-sm">
        <label class="flex items-center gap-1.5 text-slate-600"><input v-model="autoRefresh" type="checkbox" class="h-4 w-4" @change="toggleAuto" /> 每 10s 刷新</label>
        <button class="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-600" @click="load">刷新</button>
      </div>
    </div>

    <div v-if="error" class="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{{ error }}</div>

    <div class="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <div class="rounded-2xl border border-slate-200/70 bg-white p-5 shadow-sm">
        <div class="flex items-center justify-between text-xs text-slate-500"><span>服务状态</span><Activity :size="16" :class="healthy ? 'text-emerald-500' : 'text-rose-500'" /></div>
        <div class="mt-2 flex items-center gap-2 text-xl font-semibold">
          <span class="h-2.5 w-2.5 rounded-full" :class="healthy ? 'bg-emerald-500' : 'bg-rose-500'" />
          {{ summary?.status ?? '—' }}
        </div>
      </div>
      <div class="rounded-2xl border border-slate-200/70 bg-white p-5 shadow-sm">
        <div class="flex items-center justify-between text-xs text-slate-500"><span>运行时长</span><Timer :size="16" /></div>
        <div class="mt-2 text-xl font-semibold">{{ summary ? formatUptime(summary.uptimeSeconds) : '—' }}</div>
      </div>
      <div class="rounded-2xl border border-slate-200/70 bg-white p-5 shadow-sm">
        <div class="flex items-center justify-between text-xs text-slate-500"><span>实时连接</span><Radio :size="16" /></div>
        <div class="mt-2 text-xl font-semibold">{{ summary?.realtimeConnections ?? 0 }}</div>
      </div>
      <div class="rounded-2xl border border-slate-200/70 bg-white p-5 shadow-sm">
        <div class="flex items-center justify-between text-xs text-slate-500"><span>限流拦截（累计）</span><Zap :size="16" /></div>
        <div class="mt-2 text-xl font-semibold">{{ summary?.rateLimitBlocked ?? 0 }}</div>
      </div>
    </div>

    <div class="grid gap-4 lg:grid-cols-2">
      <section class="rounded-2xl border border-slate-200/70 bg-white p-5 shadow-sm">
        <div class="flex items-center justify-between"><h3 class="text-sm font-semibold">JVM 堆内存</h3><Cpu :size="16" class="text-slate-400" /></div>
        <div class="mt-4 flex items-end justify-between text-sm">
          <span class="text-2xl font-semibold">{{ summary?.heapUsedMb ?? 0 }} <span class="text-xs font-normal text-slate-400">MB</span></span>
          <span class="text-xs text-slate-400">上限 {{ summary?.heapMaxMb ?? 0 }} MB</span>
        </div>
        <div class="mt-3 h-2 overflow-hidden rounded-full bg-slate-100">
          <div class="h-full rounded-full bg-gradient-to-r from-blue-500 to-indigo-500" :style="{ width: `${heapPercent}%` }" />
        </div>
        <div class="mt-2 text-right text-xs text-slate-400">{{ heapPercent }}%</div>
      </section>

      <section class="rounded-2xl border border-slate-200/70 bg-white p-5 shadow-sm">
        <h3 class="text-sm font-semibold">实时事件</h3>
        <div class="mt-4 grid grid-cols-2 gap-4">
          <div>
            <div class="text-xs text-slate-500">已发布</div>
            <div class="mt-1 text-2xl font-semibold">{{ summary?.eventsPublished ?? 0 }}</div>
          </div>
          <div>
            <div class="text-xs text-slate-500">已投递</div>
            <div class="mt-1 text-2xl font-semibold">{{ summary?.eventsDelivered ?? 0 }}</div>
          </div>
        </div>
        <p class="mt-4 text-xs text-slate-400">采样时间：{{ summary ? new Date(summary.sampledAt).toLocaleString() : '—' }}</p>
      </section>
    </div>

    <section class="rounded-2xl border border-slate-200/70 bg-white p-5 text-sm text-slate-500 shadow-sm">
      <div v-if="loading" class="text-slate-400">加载中…</div>
      <p v-else>
        这里只展示最常用的几项。完整的 HTTP/JVM/业务指标在
        <code class="rounded bg-slate-100 px-1">/actuator/prometheus</code>，
        建议接 Grafana 看趋势和告警；后台页面负责「现在是否正常」。
      </p>
    </section>
  </div>
</template>
