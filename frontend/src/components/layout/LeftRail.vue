<script setup lang="ts">
import { computed } from 'vue';
import { Flame, UserRound } from 'lucide-vue-next';
import { useAuthStore } from '../../stores/auth';
import { useFeedStore } from '../../stores/feed';
import { levelTier, levelTitleFor, recommendScore } from '../../lib/postHelpers';

const auth = useAuthStore();
const feed = useFeedStore();

const emit = defineEmits<{
  (event: 'open-post', post: unknown): void;
  (event: 'show-level-catalog'): void;
}>();

/** 近 2 小时热度榜：点赞*1.2 + 评论*4 + 收藏*1.5 + 分享*2 + 官方/置顶加成 - 时衰减 */
const hotRankings = computed(() =>
  feed.posts.slice().sort((a, b) => recommendScore(b) - recommendScore(a)).slice(0, 8)
);
</script>

<template>
  <aside class="home-left-rail min-h-0 space-y-4 lg:h-full lg:overflow-hidden max-[880px]:hidden">
    <section v-if="auth.isAuthenticated" class="glass-panel rounded-[28px] p-4">
      <div class="flex items-center gap-3">
        <img :src="auth.currentUser.avatarUrl || ''" alt="" class="h-14 w-14 rounded-[20px] object-cover" />
        <div class="min-w-0">
          <p class="home-profile-name truncate font-black text-[#19202f]">{{ auth.currentUser.nickname }}</p>
        </div>
      </div>
      <button class="level-nameplate level-nameplate--side" :class="`level-nameplate--tier-${levelTier(auth.currentUser.level)}`" @click="emit('show-level-catalog')">
        <strong>Lv.{{ auth.currentUser.level }}</strong>
        <span>{{ levelTitleFor(auth.currentUser.level) }}</span>
      </button>
      <div class="mt-4 grid grid-cols-3 gap-2">
        <div class="rounded-[18px] bg-white/62 p-3 text-center">
          <p class="text-lg font-black">{{ auth.userStats.posts }}</p>
          <p class="text-[11px] text-[#718097]">作品</p>
        </div>
        <div class="rounded-[18px] bg-white/62 p-3 text-center">
          <p class="text-lg font-black">{{ auth.userStats.following }}</p>
          <p class="text-[11px] text-[#718097]">关注</p>
        </div>
        <div class="rounded-[18px] bg-white/62 p-3 text-center">
          <p class="text-lg font-black">{{ auth.userStats.followers }}</p>
          <p class="text-[11px] text-[#718097]">粉丝</p>
        </div>
      </div>
    </section>
    <section v-else class="glass-panel rounded-[28px] p-4">
      <div class="flex items-center gap-3">
        <span class="grid h-14 w-14 place-items-center rounded-[20px] bg-white/70 text-[#718097]">
          <UserRound :size="24" />
        </span>
        <div class="min-w-0">
          <p class="truncate text-sm font-black text-[#19202f]">未登录</p>
          <p class="mt-1 text-xs font-semibold leading-5 text-[#718097]">登录后显示你的作品和互动数据</p>
        </div>
      </div>
      <button class="mt-4 w-full rounded-full bg-[#19202f] px-4 py-2.5 text-sm font-black text-white" data-testid="rail-login" @click="auth.openDialog()">
        登录
      </button>
    </section>

    <section class="glass-panel rounded-[28px] p-4">
      <div class="flex items-end justify-between gap-3">
        <div>
          <p class="text-sm font-black text-[#19202f]">推荐</p>
          <p class="mt-1 text-xs font-semibold text-[#718097]">近 2 小时点赞评论权重榜</p>
        </div>
        <span class="rounded-full bg-[#19202f] px-2.5 py-1 text-xs font-black text-white">{{ hotRankings.length }}</span>
      </div>

      <div class="mt-3 space-y-1">
        <button
          v-for="(post, index) in hotRankings"
          :key="post.id"
          class="hot-rank-row"
          :data-hot-rank="post.id"
          @click="emit('open-post', post)"
        >
          <span class="grid h-6 w-6 place-items-center rounded-full text-xs font-black" :class="index < 3 ? 'bg-[#fff1df] text-[#ff7a00]' : 'bg-white/70 text-[#718097]'">
            <Flame v-if="index < 3" :size="14" fill="currentColor" />
            <span v-else>{{ index + 1 }}</span>
          </span>
          <span class="hot-rank-title min-w-0 flex-1 truncate text-left" :class="index < 3 ? 'font-black text-[#19202f]' : 'font-bold text-[#405067]'">{{ post.title }}</span>
        </button>
      </div>
    </section>
  </aside>
</template>
