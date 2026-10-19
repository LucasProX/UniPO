<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import dayjs from 'dayjs';
import { ChevronLeft, ChevronRight } from 'lucide-vue-next';
import PostCard from '../components/post/PostCard.vue';
import LoadingState from '../components/ui/LoadingState.vue';
import ErrorState from '../components/ui/ErrorState.vue';
import EmptyState from '../components/ui/EmptyState.vue';
import { useFeedStore } from '../stores/feed';
import { recommendScore } from '../lib/postHelpers';
import type { BoardCode, CampusPost } from '../types';

const props = defineProps<{
  board: BoardCode;
  date: string | null;
}>();

const emit = defineEmits<{
  (event: 'open-post', post: CampusPost): void;
  (event: 'open-author', post: CampusPost): void;
}>();

const feed = useFeedStore();
const feedPage = ref(1);
const feedPageSize = 10;

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
  feedPage.value = Math.min(feedPageCount.value, Math.max(1, feedPage.value + delta));
}

watch([filteredPosts, () => props.board, () => props.date], () => {
  feedPage.value = 1;
});

watch(feedPageCount, (count) => {
  if (feedPage.value > count) feedPage.value = count;
});

onMounted(() => {
  if (!feed.posts.length) feed.load();
});

function reloadFeed() {
  feed.load();
}
</script>

<template>
  <div class="home-feed hide-scrollbar min-h-0 lg:h-full lg:overflow-y-auto lg:pr-1">
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
    <div v-if="feedPageCount > 1" class="feed-pagination">
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
    </div>
  </div>
</template>
