<script setup lang="ts">
import { computed } from 'vue';
import { CheckCircle2, Flame } from 'lucide-vue-next';
import { campusApi } from '../../lib/api';
import { useAuthStore } from '../../stores/auth';
import { useFeedStore } from '../../stores/feed';
import { levelTitleFor, recommendScore } from '../../lib/postHelpers';

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

// 「签到」卡片从右栏移到这里（推荐下面）。
const maxLevel = 100;
const currentLevelNeed = computed(() => {
  const level = auth.currentUser.level;
  if (level >= maxLevel) return 0;
  const completed = Math.max(0, level - 1);
  const total = (level: number) => Math.round((level - 1) * (level - 1) * 28 + (level - 1) * 520);
  return total(level + 1) - total(level) || 520 + completed;
});

const levelProgress = computed(() => {
  const level = auth.currentUser.level;
  const total = (lv: number) => Math.round((lv - 1) * (lv - 1) * 28 + (lv - 1) * 520);
  const base = total(level);
  const need = total(level + 1) - total(level);
  if (!need) return 100;
  return Math.min(100, Math.max(0, ((auth.currentUser.xp - base) / need) * 100));
});

const dailyQuotes = [
  { text: '今天先把最小的一件事做完，心里会亮一点。', source: '校园治愈推送' },
  { text: '不需要一直满格电量，低电量也可以慢慢往前。', source: 'UniPO' },
  { text: '你已经在路上了，慢一点也不算掉队。', source: 'UniPO' }
];
const dailyQuote = computed(() => dailyQuotes[new Date().getDate() % dailyQuotes.length]);

const checkinButtonText = computed(() => {
  if (!auth.isAuthenticated) return '登录后签到';
  if (auth.checkingIn) return '签到中…';
  if (auth.checkedInToday) return `今日已签到 · 连签 ${auth.checkinStreak} 天`;
  return '签到领经验';
});

async function doCheckIn() {
  if (!auth.hasSession) {
    auth.openDialog('请先登录后签到，经验会写入后端账号');
    return;
  }
  if (auth.checkedInToday) return;
  auth.checkingIn = true;
  try {
    const result = await campusApi.checkIn();
    auth.applyCheckInView(result);
    if (result.xpGained) {
      auth.checkinCelebrating = true;
      window.setTimeout(() => { auth.checkinCelebrating = false; }, 1150);
    }
  } catch (error) {
    if (await auth.handleAuthSensitiveError(error, '登录状态失效，签到没有写入后端')) return;
  } finally {
    auth.checkingIn = false;
  }
}
</script>

<template>
  <aside class="home-left-rail min-h-0 space-y-4 lg:h-full lg:overflow-y-auto hide-scrollbar max-[880px]:hidden">
    <!-- 左上角个人用户信息卡片暂时隐藏（2026-10-20），保留热度榜作为左栏主体。 -->
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

    <section class="glass-panel rounded-[28px] p-4 checkin-card" :class="auth.checkinCelebrating ? 'checkin-card--celebrating' : ''">
      <div class="flex items-start justify-between gap-3">
        <div>
          <p class="text-xs font-black text-[#718097]">签到</p>
          <h2 class="mt-1 text-lg font-black text-[#19202f]">{{ auth.isAuthenticated ? `Lv.${auth.currentUser.level} ${levelTitleFor(auth.currentUser.level)}` : '登录后查看等级' }}</h2>
        </div>
        <span class="relative grid h-10 w-10 place-items-center rounded-[14px] bg-[#19202f] text-white">
          <CheckCircle2 :size="20" />
          <span v-if="auth.checkinCelebrating" class="xp-pop">+{{ auth.lastCheckinXp }}</span>
        </span>
      </div>

      <div class="mt-3">
        <div class="flex items-center justify-between text-[11px] font-black text-[#718097]">
          <span>{{ auth.isAuthenticated ? `${auth.currentUser.xp} XP` : '登录后同步' }}</span>
          <span>{{ auth.isAuthenticated ? `${currentLevelNeed} XP` : '-- XP' }}</span>
        </div>
        <div class="mt-2 h-2.5 overflow-hidden rounded-full bg-white/72">
          <span class="block h-full rounded-full bg-[#007aff]" :style="{ width: `${auth.isAuthenticated ? levelProgress : 0}%` }"></span>
        </div>
      </div>

      <blockquote class="daily-quote mt-3">
        <p>{{ dailyQuote.text }}</p>
        <cite>{{ dailyQuote.source }}</cite>
      </blockquote>

      <button
        class="mt-3 w-full rounded-full px-4 py-2.5 text-xs font-black transition"
        :class="auth.checkedInToday ? 'bg-white text-[#718097]' : 'bg-[#19202f] text-white hover:-translate-y-0.5'"
        :disabled="auth.checkingIn"
        data-testid="checkin"
        @click="doCheckIn"
      >
        {{ checkinButtonText }}
      </button>
    </section>
  </aside>
</template>
