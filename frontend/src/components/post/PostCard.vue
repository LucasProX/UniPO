<script setup lang="ts">
import { computed } from 'vue';
import { Heart, MessageCircle, Bookmark } from 'lucide-vue-next';
import type { BoardView, CampusComment, CampusPost } from '../../types';
import { boardLabel, compactPostTitle, fallbackAvatar, fallbackCover, formatPublishedAt, levelTitleFor, plainPostContent, roleLabel } from '../../lib/postHelpers';

const props = defineProps<{
  post: CampusPost;
  boards: BoardView[];
  comments?: CampusComment[];
  selected?: boolean;
}>();

const emit = defineEmits<{
  (event: 'open', post: CampusPost): void;
  (event: 'open-author', post: CampusPost): void;
}>();

const boardText = computed(() => boardLabel(props.post.board, props.boards));

const featuredComment = computed(() => {
  const featured = (props.comments || [])
    .filter((comment) => comment.postId === props.post.id)
    .slice()
    .sort((a, b) => b.likeCount - a.likeCount)[0];
  return featured ? `${featured.author.nickname}：${featured.content}` : '还没有热评，抢第一个补充信息。';
});
</script>

<template>
  <article
    class="po-card group"
    :class="selected ? 'po-card--selected' : ''"
    :data-post-id="post.id"
    @click="emit('open', post)"
  >
    <div class="po-card__body">
      <div class="po-card__content">
        <div class="flex items-start justify-between gap-4">
          <div class="flex min-w-0 items-center gap-3">
            <button class="author-tap" @click.stop="emit('open-author', post)">
              <img :src="post.author.avatarUrl || fallbackAvatar(post.author.nickname)" alt="" class="h-10 w-10 rounded-[14px] object-cover" />
            </button>
            <div class="min-w-0">
              <p class="truncate text-sm font-black text-[#19202f]">{{ post.author.nickname }}</p>
              <p class="mt-1 truncate text-xs font-semibold text-[#718097]">
                Lv.{{ post.author.level }} {{ levelTitleFor(post.author.level) }} · {{ roleLabel(post) }}
              </p>
            </div>
          </div>
          <span class="mini-chip" :class="post.official ? 'border-[#007aff]/20 bg-[#007aff]/10 text-[#007aff]' : ''">{{ boardText }}</span>
        </div>

        <div class="po-card__middle">
          <div class="po-card__copy">
            <h2 class="po-card__title">{{ compactPostTitle(post.title) }}</h2>
            <p class="po-card__excerpt">{{ plainPostContent(post.excerpt || post.content) }}</p>
            <div class="flex flex-wrap gap-1.5">
              <span v-for="tag in post.tags.slice(0, 3)" :key="tag" class="mini-chip">{{ tag }}</span>
            </div>
          </div>
          <div class="po-card__image-wrap">
            <img :src="post.coverUrl || fallbackCover(post.id)" alt="" class="po-card__image transition duration-300 group-hover:scale-[1.015]" />
          </div>
        </div>

        <div class="hidden">
          <span class="text-[#19202f]">热评</span>
          <span class="ml-2 line-clamp-1 inline align-bottom">{{ featuredComment }}</span>
        </div>

        <div class="mt-0 flex flex-wrap items-center justify-between gap-3 border-t border-[#e3e9f1] pt-4">
          <div class="flex gap-4 text-sm font-bold text-[#718097]">
            <span class="inline-flex items-center gap-1" :class="post.liked ? 'text-[#ff3b30]' : ''"><Heart :size="16" :fill="post.liked ? 'currentColor' : 'none'" />{{ post.likeCount }}</span>
            <span class="inline-flex items-center gap-1 text-[#007aff]"><MessageCircle :size="16" fill="currentColor" />{{ post.commentCount }}</span>
            <span class="inline-flex items-center gap-1" :class="post.favorited ? 'text-[#ffb800]' : ''"><Bookmark :size="16" :fill="post.favorited ? 'currentColor' : 'none'" />{{ post.favoriteCount }}</span>
          </div>
          <time class="text-sm font-black text-[#19202f]">{{ formatPublishedAt(post.publishedAt) }}</time>
        </div>
      </div>
    </div>
  </article>
</template>
