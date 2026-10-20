<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import dayjs from 'dayjs';
import { ChevronLeft, ChevronRight } from 'lucide-vue-next';
import { useRouter } from 'vue-router';
import PostCard from '../components/post/PostCard.vue';
import SpotlightCarousel from '../components/layout/SpotlightCarousel.vue';
import LoadingState from '../components/ui/LoadingState.vue';
import ErrorState from '../components/ui/ErrorState.vue';
import EmptyState from '../components/ui/EmptyState.vue';
import { campusApi } from '../lib/api';
import { useFeedStore } from '../stores/feed';
import { recommendScore } from '../lib/postHelpers';
import type { BoardCode, CampusPost, SpotlightView } from '../types';

const props = defineProps<{
  board: BoardCode;
  date: string | null;
}>();

const emit = defineEmits<{
  (event: 'open-post', post: CampusPost): void;
  (event: 'open-author', author: CampusPost['author']): void;
}>();

const feed = useFeedStore();
const router = useRouter();
const spotlights = ref<SpotlightView[]>([]);
const feedPage = ref(1);
const feedPageSize = 10;

/**
 * 服务端已是游标分页（stores/feed.ts 的 load/loadMore），
 * 这里的 feedPage 只是「已加载内容」的本地翻页，用于在桌面上分屏浏览。
 * 翻到最后一页且服务端还有更多时，自动续拉下一页。
 */

const filteredPosts = computed(() => feed.posts
  .filter((post) => {
    const dateMatches = props.date ? postDateKey(post) === props.date : true;
    return dateMatches && boardMatches(post);
  })
  .sort((a, b) => recommendScore(b) - recommendScore(a)));

const feedPageCount = computed(() => Math.max(1, Math.ceil(filteredPosts.value.length / feedPageSize)));
const pagedPosts = computed(() => filteredPosts.value.slice((feedPage.value - 1) * feedPageSize, feedPage.value * feedPageSize));
const canGoPrevFeedPage = computed(() => feedPage.value > 1);
const canGoNextFeedPage = computed(() => feedPage.value < feedPageCount.value);

function boardMatches(post: CampusPost) {
  return props.board === 'recommend' ? true : post.board === props.board;
}

function postDateKey(post: CampusPost) {
  // 与 App.vue 的 postDateKey 保持一致：eventDate -> deadlineAt -> publishedAt
  return dayjs(post.eventDate || post.deadlineAt || post.publishedAt).format('YYYY-MM-DD');
}

function goFeedPage(delta: number) {
  const next = Math.min(feedPageCount.value, Math.max(1, feedPage.value + delta));
  feedPage.value = next;
  // 翻到最后一页且服务端还有数据时提前续拉，避免用户看到空页
  if (next === feedPageCount.value) feed.loadMore();
}

watch([filteredPosts, () => props.board, () => props.date], () => {
  feedPage.value = 1;
});

watch(feedPageCount, (count) => {
  if (feedPage.value > count) feedPage.value = count;
});

// 首页大屏轮播只在「推荐 + 未按日期筛选」时出现，和原来的产品逻辑保持一致。
const showSpotlight = computed(() => props.board === 'recommend' && !props.date && spotlights.value.length > 0);

function openSpotlight(linkUrl: string) {
  if (linkUrl.startsWith('/')) {
    void router.push(linkUrl);
  } else {
    window.open(linkUrl, '_blank', 'noopener,noreferrer');
  }
}

onMounted(async () => {
  // board 变化时重新拉第一页：服务端分页的游标是按 tab 的排序键编码的，
  // 换 tab 不重新加载会把另一个板块的游标混进来。
  if (!feed.posts.length || feed.feedTab !== props.board) feed.load(props.board);
  // 轮播位失败不影响信息流；拿不到就当没有（不造假数据）。
  spotlights.value = await campusApi.spotlights().catch(() => []);
});

watch(() => props.board, (board) => {
  feed.load(board);
});

function reloadFeed() {
  feed.load(props.board);
}
</script>

<template>
  <div class="home-feed hide-scrollbar min-h-0 lg:h-full lg:overflow-y-auto lg:pr-1">
    <SpotlightCarousel v-if="showSpotlight" :items="spotlights" @open="openSpotlight" />
    <LoadingState v-if="feed.feedLoading && !pagedPosts.length" text="正在加载校园动态…" />
    <ErrorState v-else-if="feed.feedError && !pagedPosts.length" :message="feed.feedError" @retry="reloadFeed" />
    <EmptyState v-else-if="!pagedPosts.length" text="这个版块还没有内容" />
    <div v-else class="grid gap-5 xl:grid-cols-2">
      <PostCard
        v-for="post in pagedPosts"
        :key="post.id"
        :post="post"
        :boards="feed.boards"
        :comments="feed.comments"
        :selected="feed.selectedPost?.id === post.id"
        @open="emit('open-post', $event)"
        @open-author="emit('open-author', $event)"
      />
    </div>
    <div v-if="feedPageCount > 1 || feed.feedHasMore" class="feed-pagination">
      <button
        v-if="canGoPrevFeedPage"
        class="feed-pagination__button"
        title="上一页"
        @click="goFeedPage(-1)"
      >
        <ChevronLeft :size="20" />
      </button>
      <span class="feed-pagination__count">{{ feedPage }} / {{ feedPageCount }}</span>
      <button
        v-if="canGoNextFeedPage"
        class="feed-pagination__button"
        title="下一页"
        @click="goFeedPage(1)"
      >
        <ChevronRight :size="20" />
      </button>
      <button
        v-else-if="feed.feedHasMore"
        class="feed-pagination__button"
        title="加载更多"
        :disabled="feed.feedLoadingMore"
        @click="feed.loadMore()"
      >
        <ChevronRight :size="20" />
      </button>
    </div>
    <p v-if="feed.feedLoadingMore" class="feed-pagination__hint">正在加载更多…</p>
  </div>
</template>
