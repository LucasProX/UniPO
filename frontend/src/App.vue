<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AppShell from './layouts/AppShell.vue';
import AdminShell from './layouts/AdminShell.vue';
import PostDetailModal from './components/post/PostDetailModal.vue';
import AuthDialog from './components/modals/AuthDialog.vue';
import CourseEditorModal from './components/modals/CourseEditorModal.vue';
import ProfileEditorModal from './components/modals/ProfileEditorModal.vue';
import AvatarCropModal from './components/modals/AvatarCropModal.vue';
import { useAuthStore } from './stores/auth';
import { useFeedStore } from './stores/feed';
import { useProfileStore } from './stores/profile';
import { useMessagesStore } from './stores/messages';
import type { CampusPost } from './types';

const router = useRouter();
const route = useRoute();
const auth = useAuthStore();
const isAdminRoute = computed(() => route.path.startsWith('/admin'));
const feed = useFeedStore();
const profile = useProfileStore();
const messages = useMessagesStore();

onMounted(async () => {
  if (isAdminRoute.value) {
    // 后台不需要主站的 feed / 会话轮询；只需要把会话恢复出来给守卫用。
    await bootstrapSession();
    return;
  }
  await Promise.all([
    feed.load(),
    bootstrapSession(),
    messages.refreshConversations().catch(() => undefined)
  ]);
  messages.startConversationListPolling();
});

onUnmounted(() => {
  messages.stopConversationListPolling();
});

/** 每次启动都用 /auth/me 探一次 Cookie 会话，并拉登录后才能看的数据 */
async function bootstrapSession() {
  const valid = await auth.sessionStillValid();
  if (!valid) {
    auth.resetCheckInState();
    return;
  }
  auth.startPresenceHeartbeat();
  await Promise.all([
    profile.loadMine().catch(() => undefined),
    messages.refreshConversations().catch(() => undefined)
  ]);
}

function openAuthor(author: CampusPost['author']) {
  void profile.openAuthorProfile(author);
}
</script>

<template>
  <AdminShell v-if="isAdminRoute" />
  <AppShell v-else />

  <template v-if="!isAdminRoute">
  <PostDetailModal @open-author="openAuthor" />
  <AuthDialog />
  <CourseEditorModal />
  <ProfileEditorModal />
  <AvatarCropModal />

  <Transition name="detail-pop">
    <div v-if="auth.notice" class="auth-notice" :class="`auth-notice--${auth.noticeTone}`" data-testid="auth-notice">
      {{ auth.notice }}
    </div>
  </Transition>
  </template>
</template>

<style>
/* 后台表格里的图标按钮：主站样式不覆盖，放在后台外壳的全局样式里。 */
.admin-icon-btn {
  display: inline-grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  border: 1px solid rgb(226 232 240);
  background: #fff;
  color: rgb(71 85 105);
  transition: all 150ms ease;
}
.admin-icon-btn:hover:not(:disabled) {
  border-color: rgb(147 197 253);
  background: rgb(239 246 255);
  color: rgb(29 78 216);
}
.admin-icon-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.admin-icon-btn--on {
  border-color: rgb(59 130 246);
  background: rgb(239 246 255);
  color: rgb(29 78 216);
}
.admin-icon-btn--danger:hover:not(:disabled) {
  border-color: rgb(252 165 165);
  background: rgb(254 242 242);
  color: rgb(185 28 28);
}
</style>