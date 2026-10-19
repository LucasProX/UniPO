<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Bell, CalendarDays, Home, PlusCircle, UserRound } from 'lucide-vue-next';
import { useAuthStore } from '../../stores/auth';
import { useMessagesStore } from '../../stores/messages';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const messages = useMessagesStore();

const items = [
  { key: 'home', label: '首页', icon: Home, path: '/' },
  { key: 'schedule', label: '课程表', icon: CalendarDays, path: '/schedule' },
  { key: 'compose', label: '发PO', icon: PlusCircle, path: '/compose' },
  { key: 'messages', label: '消息', icon: Bell, path: '/messages' },
  { key: 'profile', label: '我的', icon: UserRound, path: '/profile' }
] as const;

/** 当前路由对应的 tab；/u/:uid 也算「我的」区，/messages/:id 算「消息」 */
const activeKey = computed(() => {
  const name = String(route.name || '');
  if (name === 'user-profile') return 'profile';
  if (name === 'conversation') return 'messages';
  return items.some((item) => item.key === name) ? name : 'home';
});

const activeIndex = computed(() => Math.max(0, items.findIndex((item) => item.key === activeKey.value)));

const unreadTotal = computed(() => messages.unreadCount + messages.interactionNotices.filter((notice) => notice.unread).length);

function navigate(path: string) {
  router.push(path);
}
</script>

<template>
  <nav class="fixed bottom-5 left-1/2 z-40 h-[92px] -translate-x-1/2 rounded-[32px] border border-white/70 bg-white/48 px-3 shadow-[0_24px_90px_rgba(25,38,59,0.24)] backdrop-blur-2xl">
    <div class="relative flex h-full items-center">
      <span
        class="absolute left-0 top-2 h-[78px] w-[92px] rounded-[26px] bg-white/90 shadow-[0_10px_32px_rgba(25,38,59,0.16)] transition-transform duration-300 ease-[cubic-bezier(.22,1,.36,1)]"
        :style="{ transform: `translateX(${activeIndex * 92}px)` }"
      ></span>
      <button
        v-for="item in items"
        :key="item.key"
        class="relative z-10 flex h-[84px] w-[92px] flex-col items-center justify-center gap-1 text-[10px] font-black transition"
        :class="activeKey === item.key ? 'text-[#19202f]' : 'text-[#718097]'"
        :data-dock="item.key"
        @click="navigate(item.path)"
      >
        <component :is="item.icon" :size="26" />
        <span>{{ item.label }}</span>
        <span
          v-if="item.key === 'messages' && unreadTotal"
          class="absolute right-2 top-2 grid h-4 min-w-4 place-items-center rounded-full bg-[#ff3b30] px-1 text-[9px] font-black text-white"
        >{{ unreadTotal > 99 ? '99+' : unreadTotal }}</span>
      </button>
    </div>
  </nav>
</template>
