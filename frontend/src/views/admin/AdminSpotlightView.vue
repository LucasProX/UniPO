<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { Pencil, Plus, Trash2 } from 'lucide-vue-next';
import { adminApi, type SpotlightUpsert } from '../../lib/adminApi';
import type { SpotlightView } from '../../types';

const items = ref<SpotlightView[]>([]);
const loading = ref(true);
const error = ref('');
const notice = ref('');
const modalOpen = ref(false);
const editingId = ref<number | null>(null);
const saving = ref(false);

const form = reactive({
  title: '',
  subtitle: '',
  badge: '',
  imageUrl: '',
  linkUrl: '',
  source: 'MANUAL' as 'MANUAL' | 'ALGORITHM',
  weight: 0,
  enabled: true,
  startsAt: '',
  endsAt: ''
});

function resetForm() {
  Object.assign(form, { title: '', subtitle: '', badge: '', imageUrl: '', linkUrl: '', source: 'MANUAL', weight: 0, enabled: true, startsAt: '', endsAt: '' });
  editingId.value = null;
}

function openCreate() {
  resetForm();
  modalOpen.value = true;
}

function toLocalInput(value: string | null) {
  if (!value) return '';
  const date = new Date(value);
  const offset = date.getTimezoneOffset() * 60000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}

function openEdit(item: SpotlightView) {
  editingId.value = item.id;
  Object.assign(form, {
    title: item.title,
    subtitle: item.subtitle || '',
    badge: item.badge || '',
    imageUrl: item.imageUrl || '',
    linkUrl: item.linkUrl || '',
    source: item.source === 'ALGORITHM' ? 'ALGORITHM' : 'MANUAL',
    weight: item.weight,
    enabled: item.enabled,
    startsAt: toLocalInput(item.startsAt),
    endsAt: toLocalInput(item.endsAt)
  });
  modalOpen.value = true;
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    items.value = await adminApi.spotlights();
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

async function save() {
  if (!form.title.trim()) {
    error.value = '标题不能为空';
    return;
  }
  saving.value = true;
  error.value = '';
  const payload: SpotlightUpsert = {
    title: form.title.trim(),
    subtitle: form.subtitle.trim() || null,
    badge: form.badge.trim() || null,
    imageUrl: form.imageUrl.trim() || null,
    linkUrl: form.linkUrl.trim() || null,
    source: form.source,
    weight: Number(form.weight) || 0,
    enabled: form.enabled,
    startsAt: form.startsAt ? new Date(form.startsAt).toISOString() : null,
    endsAt: form.endsAt ? new Date(form.endsAt).toISOString() : null
  };
  try {
    if (editingId.value) {
      const updated = await adminApi.updateSpotlight(editingId.value, payload);
      items.value = items.value.map((item) => (item.id === updated.id ? updated : item));
      notice.value = '已更新';
    } else {
      const created = await adminApi.createSpotlight(payload);
      items.value = [created, ...items.value];
      notice.value = '已创建';
    }
    modalOpen.value = false;
    window.setTimeout(() => { notice.value = ''; }, 2000);
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败';
  } finally {
    saving.value = false;
  }
}

async function remove(item: SpotlightView) {
  if (!window.confirm(`确定删除「${item.title}」吗？`)) return;
  try {
    await adminApi.deleteSpotlight(item.id);
    items.value = items.value.filter((row) => row.id !== item.id);
    notice.value = '已删除';
    window.setTimeout(() => { notice.value = ''; }, 2000);
  } catch (e) {
    error.value = e instanceof Error ? e.message : '删除失败';
  }
}

onMounted(load);
</script>

<template>
  <div class="space-y-5">
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h2 class="text-xl font-semibold">轮播运营</h2>
        <p class="mt-1 text-sm text-slate-500">首页大屏轮播位。权重越大越靠前；时间窗为空表示长期投放。</p>
      </div>
      <button class="flex items-center gap-1.5 rounded-lg bg-slate-900 px-3 py-1.5 text-sm text-white hover:bg-slate-800" @click="openCreate">
        <Plus :size="15" /> 新建轮播位
      </button>
    </div>

    <div v-if="error" class="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{{ error }}</div>
    <div v-if="notice" class="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-2 text-sm text-emerald-700">{{ notice }}</div>

    <div class="grid gap-4 md:grid-cols-2">
      <article v-for="item in items" :key="item.id" class="overflow-hidden rounded-2xl border border-slate-200/70 bg-white shadow-sm">
        <div class="relative h-32" :class="{ 'bg-gradient-to-br from-slate-900 via-blue-900 to-indigo-900': !item.imageUrl }">
          <img v-if="item.imageUrl" :src="item.imageUrl" alt="" class="h-full w-full object-cover" />
          <div class="absolute inset-0 bg-gradient-to-t from-slate-950/70 to-transparent" />
          <div class="absolute bottom-2 left-3 right-3 text-white">
            <div class="text-xs opacity-80">{{ item.badge || '未设置角标' }}</div>
            <div class="truncate text-sm font-semibold">{{ item.title }}</div>
          </div>
          <span class="absolute right-2 top-2 rounded-full px-2 py-0.5 text-[10px]" :class="item.enabled ? 'bg-emerald-500/90 text-white' : 'bg-slate-500/80 text-white'">
            {{ item.enabled ? '投放中' : '已下线' }}
          </span>
        </div>
        <div class="space-y-2 p-4">
          <p class="line-clamp-2 text-xs text-slate-500">{{ item.subtitle || '—' }}</p>
          <div class="flex flex-wrap items-center gap-2 text-[11px] text-slate-500">
            <span class="rounded bg-slate-100 px-1.5 py-0.5">权重 {{ item.weight }}</span>
            <span class="rounded bg-slate-100 px-1.5 py-0.5">{{ item.source }}</span>
            <span v-if="item.linkUrl" class="truncate rounded bg-slate-100 px-1.5 py-0.5">{{ item.linkUrl }}</span>
          </div>
          <div class="flex justify-end gap-1.5 pt-1">
            <button class="admin-icon-btn" title="编辑" @click="openEdit(item)"><Pencil :size="14" /></button>
            <button class="admin-icon-btn admin-icon-btn--danger" title="删除" @click="remove(item)"><Trash2 :size="14" /></button>
          </div>
        </div>
      </article>
      <div v-if="!loading && !items.length" class="grid place-items-center rounded-2xl border border-dashed border-slate-300 py-16 text-sm text-slate-400 md:col-span-2">
        还没有轮播位。首页大屏会保持隐藏，直到你添加一条。
      </div>
    </div>

    <!-- 编辑弹窗 -->
    <div v-if="modalOpen" class="fixed inset-0 z-50 grid place-items-center bg-slate-950/40 p-4" @click.self="modalOpen = false">
      <div class="w-full max-w-xl rounded-2xl bg-white p-6 shadow-2xl">
        <h3 class="text-lg font-semibold">{{ editingId ? '编辑轮播位' : '新建轮播位' }}</h3>
        <div class="mt-4 grid gap-3 sm:grid-cols-2">
          <label class="sm:col-span-2 text-xs text-slate-500">标题
            <input v-model="form.title" maxlength="80" class="mt-1 w-full rounded-lg border border-slate-200 px-3 py-2 text-sm" />
          </label>
          <label class="sm:col-span-2 text-xs text-slate-500">副标题
            <input v-model="form.subtitle" maxlength="200" class="mt-1 w-full rounded-lg border border-slate-200 px-3 py-2 text-sm" />
          </label>
          <label class="text-xs text-slate-500">角标
            <input v-model="form.badge" maxlength="20" class="mt-1 w-full rounded-lg border border-slate-200 px-3 py-2 text-sm" />
          </label>
          <label class="text-xs text-slate-500">权重（越大越靠前）
            <input v-model.number="form.weight" type="number" class="mt-1 w-full rounded-lg border border-slate-200 px-3 py-2 text-sm" />
          </label>
          <label class="sm:col-span-2 text-xs text-slate-500">跳转地址（/ 开头站内，其他新窗口）
            <input v-model="form.linkUrl" maxlength="500" class="mt-1 w-full rounded-lg border border-slate-200 px-3 py-2 text-sm" placeholder="/?board=school" />
          </label>
          <label class="sm:col-span-2 text-xs text-slate-500">图片地址（留空用品牌渐变）
            <input v-model="form.imageUrl" maxlength="500" class="mt-1 w-full rounded-lg border border-slate-200 px-3 py-2 text-sm" placeholder="/api/media/..." />
          </label>
          <label class="text-xs text-slate-500">来源
            <select v-model="form.source" class="mt-1 w-full rounded-lg border border-slate-200 px-3 py-2 text-sm">
              <option value="MANUAL">MANUAL（运营）</option>
              <option value="ALGORITHM">ALGORITHM（算法）</option>
            </select>
          </label>
          <label class="flex items-end gap-2 pb-2 text-sm text-slate-600">
            <input v-model="form.enabled" type="checkbox" class="h-4 w-4 rounded border-slate-300" /> 启用
          </label>
          <label class="text-xs text-slate-500">开始时间
            <input v-model="form.startsAt" type="datetime-local" class="mt-1 w-full rounded-lg border border-slate-200 px-3 py-2 text-sm" />
          </label>
          <label class="text-xs text-slate-500">结束时间
            <input v-model="form.endsAt" type="datetime-local" class="mt-1 w-full rounded-lg border border-slate-200 px-3 py-2 text-sm" />
          </label>
        </div>
        <div class="mt-5 flex justify-end gap-2">
          <button class="rounded-lg border border-slate-200 px-3 py-1.5 text-sm text-slate-600" @click="modalOpen = false">取消</button>
          <button class="rounded-lg bg-slate-900 px-4 py-1.5 text-sm text-white disabled:opacity-50" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
