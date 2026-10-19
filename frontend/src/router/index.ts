import { createRouter, createWebHistory } from 'vue-router';
import HomeView from '../views/HomeView.vue';

/**
 * Web / App 双端共用的路由表。
 * App 端（WebView）用同样的 path，只需把 createWebHistory 换成 createWebHashHistory 或 memory history。
 *
 * 全部视图已迁到 views/，App.vue 只做外壳（AppShell + 全局弹窗）。
 * 新增页面：在 views/ 建文件 -> 在这里加一条路由 -> 在 DockNav 加入口。
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
    component: () => import('../views/UserProfileView.vue'),
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