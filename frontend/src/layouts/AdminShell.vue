<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  Activity, ArrowLeft, BarChart3, FileText, Image, LayoutDashboard, Settings, Users
} from 'lucide-vue-next';
import { useAuthStore } from '../stores/auth';

/**
 * 管理后台外壳。和主站分开一套布局（侧边栏 + 顶栏），但共用同一份 Cookie 会话与设计系统。
 *
 * 权限：路由守卫已经拦了一道，这里再兜一次——菜单可见性不等于授权，
 * 后端的 hasAnyRole 才是最终防线。
 */
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();

const ADMIN_ROLES = ['ADMIN', 'OPERATOR', 'SCHOOL_OPERATOR', 'COLLEGE_OPERATOR', 'MAJOR_OPERATOR'];

const navGroups = [
  {
    label: '工作台',
    items: [
      { name: 'admin-dashboard', path: '/admin', label: '概览', icon: LayoutDashboard }
    ]
  },
  {
    label: '运营',
    items: [
      { name: 'admin-posts', path: '/admin/posts', label: '内容审核', icon: FileText },
      { name: 'admin-spotlights', path: '/admin/spotlights', label: '轮播运营', icon: Image }
    ]
  },
  {
    label: '数据',
    items: [
      { name: 'admin-reports', path: '/admin/reports', label: '报表', icon: BarChart3 },
      { name: 'admin-monitor', path: '/admin/monitor', label: '监控', icon: Activity }
    ]
  },
  {
    label: '管理',
    items: [
      { name: 'admin-users', path: '/admin/users', label: '用户与权限', icon: Users },
      { name: 'admin-settings', path: '/admin/settings', label: '系统设置', icon: Settings }
    ]
  }
];

const pageTitle = computed(() => (route.meta.title as string) || '管理后台');

/**
 * 直接渲染匹配到的子路由组件，而不是用 <RouterView />。
 *
 * 原因：AdminShell 由 App.vue 直接渲染，不在另一个 RouterView 里，
 * 它内部的 RouterView 深度是 0，会再次渲染父记录（AdminShell 自己）导致无限嵌套。
 * 取 matched 的最后一条就是当前子页面（lazy 组件在导航完成时已经解析好）。
 */
const adminView = computed(() => route.matched[route.matched.length - 1]?.components?.default);
const roleLabel = computed(() => auth.currentUser.role || 'GUEST');

onMounted(async () => {
  if (!auth.isAuthenticated) {
    await auth.sessionStillValid();
  }
  if (!auth.isAuthenticated || !ADMIN_ROLES.includes(auth.currentUser.role)) {
    await router.replace('/');
  }
});
</script>

<template>
  <div class="min-h-screen bg-[#f5f7fb] text-slate-900">
    <div class="flex">
      <!-- 侧边栏 -->
      <aside class="sticky top-0 hidden h-screen w-[236px] shrink-0 flex-col bg-[#0b1220] text-slate-200 lg:flex">
        <div class="px-5 pb-4 pt-6">
          <div class="flex items-center gap-2">
            <span class="grid h-9 w-9 place-items-center rounded-xl bg-gradient-to-br from-blue-500 to-indigo-500 text-base font-bold text-white">U</span>
            <div>
              <div class="text-sm font-semibold tracking-wide">UniPO Admin</div>
              <div class="text-[11px] text-slate-400">运营工作台</div>
            </div>
          </div>
        </div>

        <nav class="flex-1 space-y-5 overflow-y-auto px-3 pb-6">
          <div v-for="group in navGroups" :key="group.label">
            <div class="px-2 pb-1 text-[11px] uppercase tracking-[0.18em] text-slate-500">{{ group.label }}</div>
            <RouterLink
              v-for="item in group.items"
              :key="item.name"
              :to="item.path"
              class="group mb-0.5 flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-sm text-slate-300 transition hover:bg-white/10 hover:text-white"
              active-class="bg-white/10 text-white"
              :exact-active-class="item.path === '/admin' ? 'bg-white/10 text-white' : ''"
            >
              <component :is="item.icon" :size="16" class="text-slate-400 group-hover:text-blue-300" />
              {{ item.label }}
            </RouterLink>
          </div>
        </nav>

        <div class="border-t border-white/10 p-3">
          <RouterLink to="/" class="mb-2 flex items-center gap-2 rounded-lg px-2.5 py-2 text-xs text-slate-400 hover:bg-white/10 hover:text-white">
            <ArrowLeft :size="14" /> 返回主站
          </RouterLink>
          <div class="rounded-lg bg-white/5 px-2.5 py-2">
            <div class="truncate text-xs text-slate-200">{{ auth.currentUser.nickname }}</div>
            <div class="text-[11px] text-slate-500">{{ roleLabel }}</div>
          </div>
        </div>
      </aside>

      <!-- 主区域 -->
      <div class="min-w-0 flex-1">
        <header class="sticky top-0 z-20 border-b border-slate-200/80 bg-white/85 backdrop-blur">
          <div class="flex h-14 items-center justify-between px-4 lg:px-8">
            <div class="flex items-center gap-3">
              <span class="lg:hidden grid h-8 w-8 place-items-center rounded-lg bg-slate-900 text-xs font-bold text-white">U</span>
              <h1 class="text-base font-semibold text-slate-900">{{ pageTitle }}</h1>
              <span class="hidden rounded-full border border-emerald-200 bg-emerald-50 px-2 py-0.5 text-[11px] text-emerald-700 sm:inline">本地开发</span>
            </div>
            <div class="flex items-center gap-3 text-xs text-slate-500">
              <span class="hidden sm:inline">{{ auth.currentUser.email }}</span>
              <span class="grid h-7 w-7 place-items-center rounded-full bg-slate-900 text-[11px] font-semibold text-white">
                {{ (auth.currentUser.nickname || 'A').slice(0, 1).toUpperCase() }}
              </span>
            </div>
          </div>

          <!-- 小屏导航 -->
          <nav class="flex gap-1 overflow-x-auto border-t border-slate-100 px-3 py-2 lg:hidden">
            <RouterLink
              v-for="item in navGroups.flatMap((group) => group.items)"
              :key="item.name"
              :to="item.path"
              class="whitespace-nowrap rounded-full border border-slate-200 px-3 py-1 text-xs text-slate-600"
              active-class="border-blue-500 bg-blue-50 text-blue-700"
            >
              {{ item.label }}
            </RouterLink>
          </nav>
        </header>

        <main class="mx-auto w-full max-w-[1240px] px-4 py-6 lg:px-8">
          <component :is="adminView" />
        </main>
      </div>
    </div>
  </div>
</template>
