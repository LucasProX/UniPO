<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Bookmark, Heart, MessageCircle, Trash2 } from 'lucide-vue-next';
import { storeToRefs } from 'pinia';
import PostCard from '../components/post/PostCard.vue';
import EmptyState from '../components/ui/EmptyState.vue';
import { useAuthStore } from '../stores/auth';
import { useFeedStore } from '../stores/feed';
import { useProfileStore } from '../stores/profile';
import { compactPostTitle, fallbackAvatar, fallbackCover, formatPublishedAt, levelTier, levelTitleFor, plainPostContent, roleLabel } from '../lib/postHelpers';
import type { CampusPost } from '../types';

const auth = useAuthStore();
const feed = useFeedStore();
const profile = useProfileStore();
const router = useRouter();

const { profilePosts, profileLikedPosts, profileFavoritePosts } = storeToRefs(profile);

const tab = ref<'posts' | 'likes' | 'favorites'>('posts');

const tabs = computed(() => [
  { key: 'posts' as const, label: '作品', count: profilePosts.value.length },
  { key: 'likes' as const, label: '点赞', count: profileLikedPosts.value.length },
  { key: 'favorites' as const, label: '收藏', count: profileFavoritePosts.value.length }
]);

const sectionPosts = computed<CampusPost[]>(() => {
  if (tab.value === 'likes') return profileLikedPosts.value;
  if (tab.value === 'favorites') return profileFavoritePosts.value;
  return profilePosts.value;
});

const sectionTitle = computed(() => tabs.value.find((item) => item.key === tab.value)?.label || '作品');

const motto = computed(() => {
  const bio = auth.currentUser.bio || '';
  const match = bio.match(/座右铭：(.{1,30})$/u);
  return match?.[1]?.trim() || '今天也要把有用的事说清楚';
});

const bio = computed(() => (auth.currentUser.bio || '').replace(/\n?座右铭：.{0,30}$/u, '').trim());

onMounted(() => {
  if (!auth.hasSession) {
    auth.openDialog('请先登录后查看个人主页');
    return;
  }
  if (!profilePosts.value.length) void profile.loadMine();
});

function openPost(post: CampusPost) {
  void feed.openPost(post);
}

function openAuthor(post: CampusPost) {
  void profile.openAuthorProfile(post.author);
}

async function removePost(post: CampusPost) {
  await feed.deleteMyPost(post);
}
</script>

<template>
  <section class="glass-panel overflow-hidden rounded-[34px]">
    <div class="h-56 bg-cover bg-center" :style="{ backgroundImage: `url(/auth-campus.svg)` }"></div>
    <div class="px-6 pb-6 pt-5">
      <div class="-mt-10 flex flex-wrap items-end justify-between gap-4">
        <div class="profile-head__main">
          <img :src="auth.currentUser.avatarUrl || fallbackAvatar(auth.currentUser.nickname)" alt="" class="profile-avatar" data-testid="profile-avatar" @click="profile.openEditor()" />
          <div class="profile-head__text">
            <div class="flex flex-wrap items-center gap-2">
              <h1 class="text-3xl font-black tracking-normal" data-testid="profile-nickname">{{ auth.currentUser.nickname }}</h1>
              <span class="level-nameplate level-nameplate--profile" :class="`level-nameplate--tier-${levelTier(auth.currentUser.level)}`">
                <strong>Lv.{{ auth.currentUser.level }}</strong>
                <span>{{ levelTitleFor(auth.currentUser.level) }}</span>
              </span>
            </div>
            <p class="mt-1 text-sm font-bold text-[#718097]">UID {{ auth.currentUser.publicUid }}</p>
          </div>
        </div>
        <button class="rounded-full bg-[#19202f] px-5 py-3 text-sm font-black text-white" data-testid="profile-edit" @click="profile.openEditor()">编辑资料</button>
      </div>
      <div class="profile-social-strip">
        <div class="profile-social-pill">
          <strong>{{ auth.userStats.following }}</strong>
          <span>关注</span>
        </div>
        <div class="profile-social-pill">
          <strong>{{ auth.userStats.followers }}</strong>
          <span>粉丝</span>
        </div>
      </div>

      <div class="profile-copy">
        <p class="profile-motto">座右铭：{{ motto }}</p>
        <p class="profile-bio">{{ bio || '还没有个人简介。' }}</p>
      </div>

      <div class="profile-tabs mt-8" :style="{ '--active-profile-index': tabs.findIndex((item) => item.key === tab) }">
        <span class="profile-tabs__thumb"></span>
        <button
          v-for="item in tabs"
          :key="item.key"
          class="profile-tabs__item"
          :class="tab === item.key ? 'text-white' : 'text-[#657086]'"
          :data-tab="item.key"
          @click="tab = item.key"
        >
          <span>{{ item.label }}</span>
          <strong>{{ item.count }}</strong>
        </button>
      </div>

      <div class="mt-5 grid grid-cols-3 gap-4 max-[1180px]:grid-cols-2 max-[760px]:grid-cols-1">
        <article
          v-for="post in sectionPosts"
          :key="post.id"
          class="po-card group"
          :class="feed.selectedPost?.id === post.id ? 'po-card--selected' : ''"
          :data-post-id="post.id"
          @click="openPost(post)"
        >
          <div class="po-card__body">
            <div class="po-card__content">
              <div class="flex items-start justify-between gap-4">
                <div class="flex min-w-0 items-center gap-3">
                  <button class="author-tap" @click.stop="openAuthor(post)">
                    <img :src="post.author.avatarUrl || fallbackAvatar(post.author.nickname)" alt="" class="h-10 w-10 rounded-[14px] object-cover" />
                  </button>
                  <div class="min-w-0">
                    <p class="truncate text-sm font-black text-[#19202f]">{{ post.author.nickname }}</p>
                    <p class="mt-1 truncate text-xs font-semibold text-[#718097]">Lv.{{ post.author.level }} {{ levelTitleFor(post.author.level) }} · {{ roleLabel(post) }}</p>
                  </div>
                </div>
                <div class="flex shrink-0 items-center gap-2">
                  <button
                    v-if="tab === 'posts' && auth.hasSession && post.author.id === auth.currentUser.id"
                    class="po-card-delete"
                    :disabled="feed.isDeleting(post.id)"
                    title="删除这条 PO"
                    @click.stop="removePost(post)"
                  >
                    <Trash2 :size="15" />
                    <span>{{ feed.isDeleting(post.id) ? '删除中' : '删除' }}</span>
                  </button>
                  <span class="mini-chip" :class="post.official ? 'border-[#007aff]/20 bg-[#007aff]/10 text-[#007aff]' : ''">{{ feed.boardName(post.board) }}</span>
                </div>
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
      </div>
      <EmptyState v-if="!sectionPosts.length" :text="`${sectionTitle}还是空的`" />
    </div>
  </section>
</template>
