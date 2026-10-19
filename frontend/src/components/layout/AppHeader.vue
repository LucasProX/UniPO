<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Bell, Menu, Search } from 'lucide-vue-next';
import { useAuthStore } from '../../stores/auth';
import { useFeedStore } from '../../stores/feed';
import { useMessagesStore } from '../../stores/messages';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const feed = useFeedStore();
const messages = useMessagesStore();

defineProps<{
  accountDrawerOpen?: boolean;
}>();

const emit = defineEmits<{
  (event: 'open-drawer'): void;
}>();

const isHome = computed(() => route.name === 'home');
const activeBoard = computed(() => (route.query.board as string) || 'recommend');
const activeBoardIndex = computed(() => Math.max(0, feed.boards.findIndex((item) => item.code === activeBoard.value)));

const unreadTotal = computed(() => messages.unreadCount + messages.interactionNotices.filter((notice) => notice.unread).length);

function goHome() {
  router.push('/');
}

function selectBoard(board: string) {
  router.push({ path: '/', query: board === 'recommend' ? {} : { board } });
}

function goProfile() {
  if (!auth.hasSession) {
    auth.openDialog('请先登录后查看个人中心');
    return;
  }
  router.push('/profile');
}
</script>

<template>
  <header class="mx-auto flex h-[84px] w-[min(1600px,calc(100vw-24px))] items-center justify-between px-1">
    <button class="flex items-center gap-3 text-left" data-testid="brand" @click="goHome">
      <img src="/favicon.svg" alt="UniPO" class="h-12 w-12 rounded-[16px] shadow-lg" />
      <span>
        <span class="block text-lg font-black leading-5 text-[#19202f]">UniPO</span>
        <span class="text-xs font-semibold uppercase tracking-[0.18em] text-[#718097]">Campus Feed</span>
      </span>
    </button>

    <div v-show="isHome" class="board-slider hidden md:grid" :style="{ '--active-index': activeBoardIndex }">
      <span class="board-slider__thumb"></span>
      <button
        v-for="board in feed.boards"
        :key="board.code"
        class="board-slider__item"
        :class="activeBoard === board.code ? 'text-white' : 'text-[#657086] hover:text-[#19202f]'"
        :data-board="board.code"
        @click="selectBoard(board.code)"
      >
        {{ board.name }}
        <span v-if="board.code === 'recommend'" class="absolute -right-1 -top-2 grid h-5 min-w-5 place-items-center rounded-full bg-[#007aff] px-1 text-[10px] font-black text-white">1</span>
      </button>
    </div>

    <div class="flex items-center gap-2">
      <button class="icon-button" title="搜索">
        <Search :size="19" />
      </button>
      <button class="icon-button relative" title="消息" data-testid="nav-messages" @click="router.push('/messages')">
        <Bell :size="19" />
        <span v-if="unreadTotal" class="absolute -right-1 -top-1 grid h-5 min-w-5 place-items-center rounded-full bg-[#ff3b30] px-1 text-[10px] font-black text-white">{{ unreadTotal }}</span>
      </button>
      <button
        v-if="auth.isAuthenticated"
        class="hidden rounded-full bg-[#19202f] px-4 py-2 text-sm font-bold text-white sm:inline-flex"
        data-testid="nav-profile"
        @click="goProfile"
      >
        {{ auth.currentUser.nickname }}
      </button>
      <button v-else class="hidden rounded-full bg-[#19202f] px-4 py-2 text-sm font-bold text-white sm:inline-flex" @click="auth.openDialog()">
        登录
      </button>
      <button class="icon-button" title="菜单" data-testid="open-drawer" @click="emit('open-drawer')">
        <Menu :size="20" />
      </button>
    </div>
  </header>
</template>