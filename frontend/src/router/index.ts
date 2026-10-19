import { createRouter, createWebHistory } from 'vue-router';
import HomeView from '../views/HomeView.vue';

/**
 * Web / App 双端共用的路由表。
 * App 端（WebView）用同样的 path，只需把 createWebHistory 换成 createWebHashHistory 或 memory history。
 *
 * 迁移状态（2026-10-20）：
 * - ✅ home：已迁到 views/HomeView.vue，数据来自 stores/feed.ts
 * - ⏳ compose / schedule / messages / profile：仍是 App.vue 内的 v-else-if 旧模板，
 *    由 activeView computed（读 route.name）驱动；views/ 下是对应占位文件，
 *    等旧模板整体搬完后再切到 router-view，避免两份渲染并存。
 */
const routes = [
  { path: '/', name: 'home', component: HomeView, meta: { title: '首页' } },
  {
    path: '/compose',
    name: 'compose',
    component: () => import('../views/ComposeView.vue'),
    meta: { title: '发布 PO' }
  },
  {
    path: '/schedule',
    name: 'schedule',
    component: () => import('../views/ScheduleView.vue'),
    meta: { title: '我的课表' }
  },
  {
    path: '/messages',
    name: 'messages',
    component: () => import('../views/MessagesView.vue'),
    meta: { title: '消息' }
  },
  {
    path: '/messages/:conversationId',
    name: 'conversation',
    component: () => import('../views/MessagesView.vue'),
    meta: { title: '会话' }
  },
  {
    path: '/profile',
    name: 'profile',
    component: () => import('../views/ProfileView.vue'),
    meta: { title: '我的主页' }
  },
  {
    path: '/u/:uid',
    name: 'user-profile',
    component: () => import('../views/ProfileView.vue'),
    meta: { title: '主页' }
  }
];

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 };
  }
});

router.afterEach((to) => {
  const title = to.meta.title as string | undefined;
  if (title) document.title = `${title} · UniPO`;
});

export default router;
