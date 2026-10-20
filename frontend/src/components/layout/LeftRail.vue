<script setup lang="ts">
import { computed } from 'vue';
import { Flame } from 'lucide-vue-next';
import { useFeedStore } from '../../stores/feed';
import { recommendScore } from '../../lib/postHelpers';

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
  </aside>
</template>
