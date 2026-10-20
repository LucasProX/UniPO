<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Search, ShieldOff, ShieldCheck } from 'lucide-vue-next';
import { adminApi, type AdminUser } from '../../lib/adminApi';
import { useAuthStore } from '../../stores/auth';

const auth = useAuthStore();
const users = ref<AdminUser[]>([]);
const keyword = ref('');
const loading = ref(true);
const error = ref('');
const notice = ref('');
const busyId = ref<number | null>(null);

const ROLES = [
  { value: 'USER', label: '普通用户' },
  { value: 'OPERATOR', label: '运营' },
  { value: 'SCHOOL_OPERATOR', label: '校级运营' },
  { value: 'COLLEGE_OPERATOR', label: '院级运营' },
  { value: 'MAJOR_OPERATOR', label: '专业运营' },
  { value: 'ADMIN', label: '管理员' }
];

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  if (!q) return users.value;
  return users.value.filter((u) => u.email.toLowerCase().includes(q) || u.nickname.toLowerCase().includes(q) || u.publicUid.includes(q));
});

async function load() {
  loading.value = true;
  error.value = '';
  try {
    users.value = await adminApi.users();
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function flash(message: string) {
  notice.value = message;
  window.setTimeout(() => { notice.value = ''; }, 2000);
}

async function toggleStatus(user: AdminUser) {
  const next = user.status === 'active' ? 'disabled' : 'active';
  if (next === 'disabled' && !window.confirm(`确定封禁「${user.nickname}」吗？`)) return;
  busyId.value = user.id;
  try {
    const updated = await adminApi.updateUserStatus(user.id, next);
    users.value = users.value.map((u) => (u.id === updated.id ? updated : u));
    flash(next === 'active' ? '已解封' : '已封禁');
  } catch (e) {
    error.value = e instanceof Error ? e.message : '操作失败';
  } finally {
    busyId.value = null;
  }
}

async function changeRole(user: AdminUser, role: string) {
  if (user.role === role) return;
  if (!window.confirm(`把「${user.nickname}」的角色改为 ${role}？`)) return;
  busyId.value = user.id;
  try {
    const updated = await adminApi.updateUserRole(user.id, role, user.operatorScope ?? null);
    users.value = users.value.map((u) => (u.id === updated.id ? updated : u));
    flash('角色已更新');
  } catch (e) {
    error.value = e instanceof Error ? e.message : '操作失败';
  } finally {
    busyId.value = null;
  }
}

onMounted(load);
</script>

<template>
  <div class="space-y-5">
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h2 class="text-xl font-semibold">用户与权限</h2>
        <p class="mt-1 text-sm text-slate-500">封禁/解封、调整角色。角色的最终校验在后端，这里只是入口。</p>
      </div>
      <div class="flex items-center gap-2">
        <div class="flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5">
          <Search :size="14" class="text-slate-400" />
          <input v-model="keyword" placeholder="搜索邮箱 / 昵称 / uid" class="w-52 bg-transparent text-sm outline-none" />
        </div>
        <button class="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-600" @click="load">刷新</button>
      </div>
    </div>

    <div v-if="error" class="rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{{ error }}</div>
    <div v-if="notice" class="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-2 text-sm text-emerald-700">{{ notice }}</div>

    <div class="overflow-hidden rounded-2xl border border-slate-200/70 bg-white shadow-sm">
      <table class="w-full text-left text-sm">
        <thead class="border-b border-slate-100 bg-slate-50/70 text-xs text-slate-500">
          <tr>
            <th class="px-4 py-3 font-medium">用户</th>
            <th class="px-3 py-3 font-medium">角色</th>
            <th class="px-3 py-3 font-medium">状态</th>
            <th class="px-3 py-3 font-medium">注册时间</th>
            <th class="px-4 py-3 text-right font-medium">操作</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-100">
          <tr v-if="loading"><td colspan="5" class="px-4 py-10 text-center text-sm text-slate-400">加载中…</td></tr>
          <tr v-for="user in filtered" :key="user.id" class="hover:bg-slate-50/60">
            <td class="px-4 py-3">
              <div class="font-medium text-slate-900">{{ user.nickname }}</div>
              <div class="text-xs text-slate-400">{{ user.email }} · {{ user.publicUid }}</div>
            </td>
            <td class="px-3 py-3">
              <select
                class="rounded-lg border border-slate-200 bg-white px-2 py-1 text-xs"
                :value="user.role"
                :disabled="busyId === user.id || user.id === auth.currentUser.id"
                @change="changeRole(user, ($event.target as HTMLSelectElement).value)"
              >
                <option v-for="role in ROLES" :key="role.value" :value="role.value">{{ role.label }}</option>
              </select>
            </td>
            <td class="px-3 py-3">
              <span class="rounded-full px-2 py-0.5 text-xs" :class="user.status === 'active' ? 'bg-emerald-50 text-emerald-700' : 'bg-rose-50 text-rose-600'">
                {{ user.status === 'active' ? '正常' : '已封禁' }}
              </span>
            </td>
            <td class="px-3 py-3 text-xs text-slate-500">{{ new Date(user.createdAt).toLocaleDateString() }}</td>
            <td class="px-4 py-3 text-right">
              <button
                class="admin-icon-btn"
                :class="user.status === 'active' ? 'admin-icon-btn--danger' : ''"
                :title="user.status === 'active' ? '封禁' : '解封'"
                :disabled="busyId === user.id || user.id === auth.currentUser.id"
                @click="toggleStatus(user)"
              >
                <ShieldOff v-if="user.status === 'active'" :size="14" />
                <ShieldCheck v-else :size="14" />
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
