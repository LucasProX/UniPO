import { createRouter, createWebHistory } from 'vue-router';
import HomeView from '../views/HomeView.vue';
import { useAuthStore } from '../stores/auth';

/**
 * Web / App 双端共用的路由表。
 * App 端（WebView）用同样的 path，只需把 createWebHistory 换成 createWebHashHistory 或 memory history。
 *
 * 全部视图已迁到 views/，App.vue 只做外壳（AppShell + 全局弹窗）。
 * 新增页面：在 views/ 建文件 -> 在这里加一条路由 -> 在 DockNav 加入口。
 *
 * 管理后台：同 SPA 的 /admin 子树，懒加载 + 角色守卫；不与主站共享布局。
 */
const ADMIN_ROLES = ['ADMIN', 'OPERATOR', 'SCHOOL_OPERATOR', 'COLLEGE_OPERATOR', 'MAJOR_OPERATOR'];

const routes = [
  { path: '/', name: 'home', component: HomeView, meta: { title: '首页' } },
  { path: '/compose', name: 'compose', component: () => import('../views/ComposeView.vue'), meta: { title: '发布 PO' } },
  { path: '/schedule', name: 'schedule', component: () => import('../views/ScheduleView.vue'), meta: { title: '我的课表' } },
  { path: '/messages', name: 'messages', component: () => import('../views/MessagesView.vue'), meta: { title: '消息' } },
  { path: '/messages/:conversationId', name: 'conversation', component: () => import('../views/MessagesView.vue'), meta: { title: '会话' } },
  { path: '/profile', name: 'profile', component: () => import('../views/ProfileView.vue'), meta: { title: '我的主页' } },
  { path: '/u/:uid', name: 'user-profile', component: () => import('../views/UserProfileView.vue'), meta: { title: '主页' } },
  {
    path: '/admin',
    component: () => import('../layouts/AdminShell.vue'),
    meta: { requiresAdmin: true },
    children: [
      { path: '', name: 'admin-dashboard', component: () => import('../views/admin/AdminDashboardView.vue'), meta: { title: '概览', requiresAdmin: true } },
      { path: 'posts', name: 'admin-posts', component: () => import('../views/admin/AdminContentView.vue'), meta: { title: '内容审核', requiresAdmin: true } },
      { path: 'spotlights', name: 'admin-spotlights', component: () => import('../views/admin/AdminSpotlightView.vue'), meta: { title: '轮播运营', requiresAdmin: true } },
      { path: 'users', name: 'admin-users', component: () => import('../views/admin/AdminUsersView.vue'), meta: { title: '用户与权限', requiresAdmin: true } },
      { path: 'reports', name: 'admin-reports', component: () => import('../views/admin/AdminReportsView.vue'), meta: { title: '报表', requiresAdmin: true } },
      { path: 'monitor', name: 'admin-monitor', component: () => import('../views/admin/AdminMonitorView.vue'), meta: { title: '监控', requiresAdmin: true } },
      { path: 'settings', name: 'admin-settings', component: () => import('../views/admin/AdminSettingsView.vue'), meta: { title: '系统设置', requiresAdmin: true } }
    ]
  }
];

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 };
  }
});

/**
 * 后台守卫：会话可能还没恢复（App.vue 的 bootstrap 是异步的），所以先探一次 /auth/me。
 * 权限判断两层：这里挡人，后端 hasAnyRole 挡请求。
 */
router.beforeEach(async (to) => {
  if (!to.meta.requiresAdmin) return true;
  const auth = useAuthStore();
  if (!auth.isAuthenticated) {
    await auth.sessionStillValid();
  }
  if (!auth.isAuthenticated || !ADMIN_ROLES.includes(auth.currentUser.role)) {
    return { path: '/' };
  }
  return true;
});

router.afterEach((to) => {
  const title = to.meta.title as string | undefined;
  if (title) document.title = `${title} · UniPO`;
});

export default router;
