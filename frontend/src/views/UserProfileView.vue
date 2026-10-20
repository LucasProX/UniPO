<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Bookmark, Flag, Heart, MessageCircle, MoreHorizontal, Send, UserX } from 'lucide-vue-next';
import { storeToRefs } from 'pinia';
import EmptyState from '../components/ui/EmptyState.vue';
import FollowListModal from '../components/profile/FollowListModal.vue';
import { campusApi } from '../lib/api';
import { formatCount } from '../lib/format';
import { useAuthStore } from '../stores/auth';
import { useFeedStore } from '../stores/feed';
import { useMessagesStore } from '../stores/messages';
import { useProfileStore } from '../stores/profile';
import { compactPostTitle, fallbackAvatar, fallbackCover, formatPublishedAt, levelTier, levelTitleFor, plainPostContent, roleLabel } from '../lib/postHelpers';
import type { CampusPost } from '../types';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const feed = useFeedStore();
const messages = useMessagesStore();
const profile = useProfileStore();

const { viewedProfile, viewedProfilePosts, viewedProfileLikedPosts, viewedProfileFavoritePosts } = storeToRefs(profile);

const tab = ref<'posts' | 'likes' | 'favorites'>('posts');
const loading = ref(true);
const followModalOpen = ref(false);
const followModalTab = ref<'followers' | 'following'>('followers');
const menuOpen = ref(false);
const reportOpen = ref(false);
const reportCategory = ref('');
const reportDetail = ref('');
const submitting = ref(false);
const actionError = ref('');
const actionNotice = ref('');

const reportCategories = [
  { key: 'spam', label: '垃圾广告', hint: '刷屏、营销、引流' },
  { key: 'fraud', label: '诈骗 / 虚假信息', hint: '兼职、贷款、冒充客服' },
  { key: 'harassment', label: '人身攻击', hint: '辱骂、骚扰、威胁' },
  { key: 'porn', label: '色情低俗', hint: '裸露、性暗示内容' },
  { key: 'impersonation', label: '冒充他人', hint: '冒充同学、老师、官方' },
  { key: 'other', label: '其他', hint: '不属于以上分类' }
];

const uid = computed(() => String(route.params.uid || ''));

const tabs = computed(() => [
  { key: 'posts' as const, label: '作品', count: viewedProfilePosts.value.length },
  { key: 'likes' as const, label: '点赞', count: viewedProfileLikedPosts.value.length },
  { key: 'favorites' as const, label: '收藏', count: viewedProfileFavoritePosts.value.length }
]);

const sectionPosts = computed<CampusPost[]>(() => {
  if (tab.value === 'likes') return viewedProfileLikedPosts.value;
  if (tab.value === 'favorites') return viewedProfileFavoritePosts.value;
  return viewedProfilePosts.value;
});

const sectionTitle = computed(() => tabs.value.find((item) => item.key === tab.value)?.label || '作品');

onMounted(async () => {
  loading.value = true;
  // 直接打开 /u/:uid 或刷新时，App.vue 的 bootstrap 可能还没完成；
  // 先等服务端会话探活，否则会把「已登录」误判成访客并弹登录框。
  if (!auth.hasSession) {
    await auth.sessionStillValid();
  }
  const result = await profile.openAuthorProfile({ uid: uid.value, id: 0 });
  if (result === 'mine') router.replace('/profile');
  loading.value = false;
});

function openPost(post: CampusPost) {
  void feed.openPost(post);
}

function openAuthor(post: CampusPost) {
  void profile.openAuthorProfile(post.author);
}

async function startConversation() {
  if (!viewedProfile.value) return;
  const conversation = await messages.startConversationWith(viewedProfile.value.profile);
  if (conversation) router.push(`/messages/${conversation.id}`);
}

function openFollowList(target: 'followers' | 'following') {
  followModalTab.value = target;
  followModalOpen.value = true;
}

function handleFollowUpdated(payload: { followersDelta: number; followingDelta: number }) {
  if (!viewedProfile.value) return;
  viewedProfile.value.stats.followers = Math.max(0, viewedProfile.value.stats.followers + payload.followersDelta);
  viewedProfile.value.stats.following = Math.max(0, viewedProfile.value.stats.following + payload.followingDelta);
}

async function toggleBlock() {
  menuOpen.value = false;
  if (!viewedProfile.value) return;
  const target = viewedProfile.value;
  const blocking = !target.blocked;
  if (blocking && !window.confirm(`拉黑「${target.profile.nickname}」后，你们会互相取关，对方将无法关注或私信你。确定吗？`)) return;
  actionError.value = '';
  try {
    if (blocking) await campusApi.blockUser(target.profile.publicUid);
    else await campusApi.unblockUser(target.profile.publicUid);
    await profile.openAuthorProfile({ uid: uid.value, id: 0 });
    actionNotice.value = blocking ? '已拉黑该用户' : '已取消拉黑';
    window.setTimeout(() => { actionNotice.value = ''; }, 2000);
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : '操作失败';
  }
}

async function submitReport() {
  if (!viewedProfile.value) return;
  if (!reportCategory.value) {
    actionError.value = '请选择举报原因';
    return;
  }
  submitting.value = true;
  actionError.value = '';
  try {
    await campusApi.reportUser(viewedProfile.value.profile.publicUid, {
      category: reportCategory.value,
      detail: reportDetail.value.trim() || undefined
    });
    reportOpen.value = false;
    reportCategory.value = '';
    reportDetail.value = '';
    actionNotice.value = '举报已提交，我们会尽快处理';
    window.setTimeout(() => { actionNotice.value = ''; }, 3000);
  } catch (e) {
    actionError.value = e instanceof Error ? e.message : '举报失败，请稍后再试';
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <section v-if="loading" class="glass-panel rounded-[34px] p-6">
    <p class="text-sm font-bold text-[#718097]">主页加载中…</p>
  </section>

  <section v-else-if="viewedProfile" class="glass-panel overflow-hidden rounded-[34px]">
    <div class="h-56 bg-cover bg-center" :style="{ backgroundImage: `url(/auth-campus.svg)` }"></div>
    <div class="px-6 pb-6 pt-5">
      <div class="-mt-10 flex flex-wrap items-end justify-between gap-4">
        <div class="profile-head__main">
          <img :src="viewedProfile.profile.avatarUrl || fallbackAvatar(viewedProfile.profile.nickname)" alt="" class="profile-avatar" />
          <div class="profile-head__text">
            <div class="flex flex-wrap items-center gap-2">
              <h1 class="text-3xl font-black tracking-normal" data-testid="user-profile-nickname">{{ viewedProfile.profile.nickname }}</h1>
              <span class="level-nameplate level-nameplate--profile" :class="`level-nameplate--tier-${levelTier(viewedProfile.profile.level)}`">
                <strong>Lv.{{ viewedProfile.profile.level }}</strong>
                <span>{{ levelTitleFor(viewedProfile.profile.level) }}</span>
              </span>
            </div>
            <p class="mt-1 text-sm font-bold text-[#718097]">UID {{ viewedProfile.profile.publicUid }} · {{ viewedProfile.profile.college || '试点大学' }} {{ viewedProfile.profile.grade || '' }}</p>
          </div>
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <button
            v-if="!viewedProfile.mine && !viewedProfile.blocked && !viewedProfile.blockedBy"
            class="rounded-full px-5 py-3 text-sm font-black transition"
            :class="viewedProfile.following ? 'bg-white text-[#19202f]' : 'bg-[#19202f] text-white'"
            data-testid="user-follow"
            @click="profile.toggleFollow()"
          >
            {{ viewedProfile.following ? '已关注' : '关注' }}
          </button>
          <button
            v-if="!viewedProfile.mine && viewedProfile.following && !viewedProfile.blocked"
            class="inline-flex items-center gap-2 rounded-full bg-[#007aff] px-5 py-3 text-sm font-black text-white transition hover:-translate-y-0.5"
            data-testid="user-message"
            @click="startConversation"
          >
            <Send :size="16" />
            私信
          </button>
          <div v-if="!viewedProfile.mine" class="relative">
            <button
              class="grid h-11 w-11 place-items-center rounded-full border border-[#e3e9f1] bg-white/80 text-[#405067] transition hover:bg-white"
              title="更多"
              data-testid="user-more"
              @click.stop="menuOpen = !menuOpen"
            >
              <MoreHorizontal :size="18" />
            </button>
            <div v-if="menuOpen" class="fixed inset-0 z-10" @click="menuOpen = false" />
            <div v-if="menuOpen" class="absolute right-0 top-full z-20 mt-2 w-44 overflow-hidden rounded-2xl border border-[#e3e9f1] bg-white py-1 shadow-xl">
              <button class="flex w-full items-center gap-2 px-4 py-2.5 text-left text-sm text-[#405067] hover:bg-[#f4f7fb]" @click="toggleBlock">
                <UserX :size="15" />
                {{ viewedProfile.blocked ? '取消拉黑' : '拉黑该用户' }}
              </button>
              <button class="flex w-full items-center gap-2 px-4 py-2.5 text-left text-sm text-[#ff3b30] hover:bg-[#fff4f4]" @click="menuOpen = false; reportOpen = true">
                <Flag :size="15" />
                举报该用户
              </button>
            </div>
          </div>
        </div>
      </div>

      <div class="profile-social-strip profile-social-strip--plain">
        <button type="button" class="profile-social-pill" @click="openFollowList('following')">
          <strong>{{ formatCount(viewedProfile.stats.following) }}</strong>
          <span>关注</span>
        </button>
        <button type="button" class="profile-social-pill" @click="openFollowList('followers')">
          <strong>{{ formatCount(viewedProfile.stats.followers) }}</strong>
          <span>粉丝</span>
        </button>
      </div>

      <div v-if="viewedProfile.blocked" class="mt-3 flex items-center justify-between rounded-2xl border border-[#ffd9d9] bg-[#fff6f6] px-4 py-2.5 text-xs font-bold text-[#c22a2a]">
        <span>已拉黑该用户，你们互相不可见关注关系，也无法私信。</span>
        <button class="rounded-full bg-white px-3 py-1 text-[#c22a2a]" @click="toggleBlock">取消拉黑</button>
      </div>
      <div v-if="viewedProfile.blockedBy" class="mt-3 rounded-2xl border border-[#e3e9f1] bg-[#f7f9fc] px-4 py-2.5 text-xs font-bold text-[#718097]">
        对方已将你拉黑，无法关注或私信。
      </div>
      <div v-if="actionNotice" class="mt-3 rounded-2xl border border-[#c8f0d8] bg-[#f2fbf5] px-4 py-2.5 text-xs font-bold text-[#1d7a43]">{{ actionNotice }}</div>
      <div v-if="actionError" class="mt-3 rounded-2xl border border-[#ffd9d9] bg-[#fff6f6] px-4 py-2.5 text-xs font-bold text-[#c22a2a]">{{ actionError }}</div>

      <div class="profile-copy">
        <p class="profile-motto">座右铭：{{ viewedProfile.profile.bio?.match(/座右铭：(.{1,30})$/u)?.[1] || '还没有座右铭' }}</p>
        <p class="profile-bio">{{ (viewedProfile.profile.bio || '').replace(/\n?座右铭：.{0,30}$/u, '').trim() || '还没有个人简介。' }}</p>
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

      <div class="mt-8 flex items-center justify-between gap-3">
        <h2 class="text-xl font-black text-[#19202f]">{{ sectionTitle }}（{{ sectionPosts.length }}条）</h2>
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
                  <img :src="post.author.avatarUrl || fallbackAvatar(post.author.nickname)" alt="" class="h-10 w-10 rounded-[14px] object-cover" />
                  <div class="min-w-0">
                    <p class="truncate text-sm font-black text-[#19202f]">{{ post.author.nickname }}</p>
                    <p class="mt-1 truncate text-xs font-semibold text-[#718097]">Lv.{{ post.author.level }} {{ levelTitleFor(post.author.level) }} · {{ roleLabel(post) }}</p>
                  </div>
                </div>
                <span class="mini-chip" :class="post.official ? 'border-[#007aff]/20 bg-[#007aff]/10 text-[#007aff]' : ''">{{ feed.boardName(post.board) }}</span>
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

  <section v-else class="glass-panel rounded-[34px] p-6">
    <p class="text-sm font-bold text-[#718097]">没有找到这个用户的主页。</p>
  </section>

  <FollowListModal
    v-if="viewedProfile"
    :open="followModalOpen"
    :uid="viewedProfile.profile.publicUid"
    :mine="viewedProfile.mine"
    :initial-tab="followModalTab"
    @close="followModalOpen = false"
    @updated="handleFollowUpdated"
  />

  <div v-if="reportOpen" class="fixed inset-0 z-50 grid place-items-center bg-slate-950/40 p-4" @click.self="reportOpen = false">
    <div class="w-full max-w-md rounded-[26px] bg-white p-6 shadow-2xl">
      <h3 class="text-lg font-black text-[#19202f]">举报 {{ viewedProfile?.profile.nickname }}</h3>
      <p class="mt-1 text-xs font-semibold text-[#718097]">举报会提交给运营审核，请选择最接近的原因。</p>

      <div class="mt-4 space-y-1.5">
        <label
          v-for="item in reportCategories"
          :key="item.key"
          class="flex cursor-pointer items-center justify-between rounded-xl border px-3 py-2.5 transition"
          :class="reportCategory === item.key ? 'border-[#19202f] bg-[#f4f7fb]' : 'border-[#e3e9f1] hover:bg-[#f7f9fc]'"
        >
          <span>
            <span class="block text-sm font-bold text-[#19202f]">{{ item.label }}</span>
            <span class="mt-0.5 block text-[11px] text-[#718097]">{{ item.hint }}</span>
          </span>
          <input v-model="reportCategory" type="radio" :value="item.key" class="h-4 w-4" />
        </label>
      </div>

      <textarea
        v-model="reportDetail"
        rows="3"
        maxlength="500"
        placeholder="补充说明（选填，最多 500 字）"
        class="mt-3 w-full resize-none rounded-xl border border-[#e3e9f1] px-3 py-2 text-sm outline-none focus:border-[#19202f]"
      />

      <div v-if="actionError" class="mt-2 text-xs font-bold text-[#c22a2a]">{{ actionError }}</div>

      <div class="mt-4 flex justify-end gap-2">
        <button class="rounded-full border border-[#e3e9f1] px-4 py-2 text-sm font-bold text-[#405067]" @click="reportOpen = false">取消</button>
        <button class="rounded-full bg-[#19202f] px-4 py-2 text-sm font-bold text-white disabled:opacity-50" :disabled="submitting" @click="submitReport">
          {{ submitting ? '提交中…' : '提交举报' }}
        </button>
      </div>
    </div>
  </div>
</template>