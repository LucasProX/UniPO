<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AppHeader from '../components/layout/AppHeader.vue';
import DockNav from '../components/layout/DockNav.vue';
import LeftRail from '../components/layout/LeftRail.vue';
import RightRail from '../components/layout/RightRail.vue';
import AccountDrawer from '../components/modals/AccountDrawer.vue';
import LevelCatalogModal from '../components/modals/LevelCatalogModal.vue';
import { useAuthStore } from '../stores/auth';
import { useFeedStore } from '../stores/feed';
import type { CampusPost } from '../types';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const feed = useFeedStore();

const accountDrawerOpen = ref(false);
const showLevelCatalog = ref(false);

const isHome = computed(() => route.name === 'home');
const selectedDate = computed(() => (route.query.date as string) || null);

function openPost(post: CampusPost) {
  void feed.openPost(post);
}

/**
 * 点头像/昵称进作者主页。
 *
 * 注意：这里只负责「导航」，数据由 UserProfileView 自己按 /u/:uid 加载。
 * 之前打开了 profile store 的 openAuthorProfile 却没有 push 路由，数据加载完页面还停在首页，
 * 看起来就是「点了头像没反应」。
 */
function openAuthor(author: CampusPost['author']) {
  if (!auth.hasSession) {
    auth.openDialog('请先登录后查看个人主页');
    return;
  }
  if (auth.isAuthenticated && author.uid === auth.currentUser.publicUid) {
    void router.push('/profile');
    return;
  }
  void router.push(`/u/${author.uid}`);
}

function selectDate(dateKey: string | null) {
  const query: Record<string, string> = { ...route.query } as Record<string, string>;
  if (dateKey) query.date = dateKey;
  else delete query.date;
  void router.push({ path: '/', query });
}
</script>

<template>
  <div class="app-shell relative min-h-screen overflow-x-hidden pb-28 lg:h-screen lg:overflow-hidden">
    <AppHeader @open-drawer="accountDrawerOpen = true" />

    <main
      class="relative z-10 mx-auto grid min-h-0 w-[min(1600px,calc(100vw-24px))] gap-5 lg:h-[calc(100vh-100px)] lg:overflow-hidden max-[880px]:grid-cols-1 max-[880px]:overflow-visible"
      :class="isHome ? 'grid-cols-[280px_minmax(0,1fr)_280px] max-[1180px]:grid-cols-[240px_minmax(0,1fr)]' : 'grid-cols-1'"
    >
      <LeftRail v-if="isHome" class="max-[880px]:hidden" @open-post="openPost($event as CampusPost)" @show-level-catalog="showLevelCatalog = true" />

      <section
        class="min-h-0 min-w-0 lg:h-full"
        :class="isHome ? '' : 'mx-auto w-[min(1180px,100%)] lg:overflow-y-auto hide-scrollbar max-[880px]:overflow-visible'"
      >
        <router-view v-slot="{ Component }">
          <component
            :is="Component"
            :board="(route.query.board as string) || 'recommend'"
            :date="selectedDate"
            @open-post="openPost"
            @open-author="openAuthor"
          />
        </router-view>
      </section>

      <RightRail v-if="isHome" class="max-[1180px]:hidden" :selected-date="selectedDate" @select-date="selectDate" />
    </main>

    <DockNav />

    <AccountDrawer :open="accountDrawerOpen" @close="accountDrawerOpen = false" />
    <LevelCatalogModal :open="showLevelCatalog" @close="showLevelCatalog = false" />
  </div>
</template>