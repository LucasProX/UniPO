<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import AppShell from './layouts/AppShell.vue';
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
const auth = useAuthStore();
const feed = useFeedStore();
const profile = useProfileStore();
const messages = useMessagesStore();

onMounted(async () => {
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
  <AppShell />

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