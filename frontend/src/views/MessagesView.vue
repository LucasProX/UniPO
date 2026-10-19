<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ChevronLeft, Heart, MessageCircle, Send } from 'lucide-vue-next';
import { storeToRefs } from 'pinia';
import { useMessagesStore } from '../stores/messages';
import { useFeedStore } from '../stores/feed';
import { fallbackAvatar, formatPublishedAt, levelTitleFor } from '../lib/postHelpers';

const route = useRoute();
const router = useRouter();
const messages = useMessagesStore();
const feed = useFeedStore();

const {
  conversations, interactionNotices, activeConversationId, activeConversation,
  activeConversationMessages, messageInput, unreadCount
} = storeToRefs(messages);

const noticeTab = ref<'all' | 'like' | 'favorite' | 'comment' | 'reply' | 'message'>('all');
const profileTabLocal = ref('posts');

const messageTabs = [
  { key: 'all', label: '全部', icon: MessageCircle },
  { key: 'like', label: '点赞', icon: Heart },
  { key: 'favorite', label: '收藏', icon: Heart },
  { key: 'comment', label: '评论', icon: MessageCircle },
  { key: 'reply', label: '回复', icon: MessageCircle }
] as const;

const messageTabUnreadCounts = computed(() => {
  const counts: Record<string, number> = {};
  for (const notice of interactionNotices.value) {
    const key = notice.type === 'reply' ? 'reply' : notice.type;
    counts[key] = (counts[key] || 0) + (notice.unread ? 1 : 0);
  }
  return counts;
});

const activeNotices = computed(() =>
  noticeTab.value === 'all'
    ? interactionNotices.value
    : interactionNotices.value.filter((notice) => notice.type === noticeTab.value)
);

const privateConversations = computed(() => conversations.value);

onMounted(() => {
  messages.startConversationListPolling();
  const conversationId = route.params.conversationId;
  if (conversationId) {
    const found = conversations.value.find((item) => item.id === Number(conversationId));
    if (found) void messages.openConversation(found);
  }
  else messages.stopActiveConversationPolling();
});

onUnmounted(() => {
  messages.stopConversationListPolling();
  messages.stopActiveConversationPolling();
});

function openConversation(conversation: { id: number }) {
  const found = conversations.value.find((item) => item.id === conversation.id);
  if (!found) return;
  router.push(`/messages/${found.id}`);
  void messages.openConversation(found);
}

function closeConversation() {
  messages.closeConversation();
  router.push('/messages');
}

async function sendConversationMessage() {
  await messages.sendMessage();
}

function noticeText(notice: (typeof interactionNotices.value)[number]) {
  if (notice.type === 'like') return `${notice.actor.nickname} 赞了你的 PO`;
  if (notice.type === 'favorite') return `${notice.actor.nickname} 收藏了你的 PO`;
  if (notice.type === 'comment') return `${notice.actor.nickname} 评论了你的 PO`;
  if (notice.type === 'reply') return `${notice.actor.nickname} 回复了你的评论`;
  return `${notice.actor.nickname} 给你发来了新消息`;
}

function openNoticePost(notice: (typeof interactionNotices.value)[number]) {
  if (!notice.post) return;
  void feed.openPost(notice.post as never);
}
</script>

<template>
  <section v-if="activeConversation" class="glass-panel message-thread-panel rounded-[34px]">
    <div class="message-thread-head">
      <button class="icon-button" title="返回" data-testid="thread-back" @click="closeConversation">
        <ChevronLeft :size="20" />
      </button>
      <img :src="activeConversation.peer.avatarUrl || fallbackAvatar(activeConversation.peer.nickname)" alt="" class="h-[52px] w-[52px] rounded-[18px] object-cover" />
      <div class="min-w-0">
        <div class="message-thread-titleline">
          <h1 class="truncate text-2xl font-black tracking-normal text-[#19202f]">{{ activeConversation.peer.nickname }}</h1>
          <span class="level-nameplate level-nameplate--chat">
            <strong>Lv.{{ activeConversation.peer.level }}</strong>
            <span>{{ levelTitleFor(activeConversation.peer.level) }}</span>
          </span>
        </div>
        <span class="presence-pill mt-1" :class="activeConversation.peer.online ? 'presence-pill--online' : ''">
          <span></span>
          {{ activeConversation.peer.online ? '在线' : '离线' }}
        </span>
      </div>
    </div>

    <div class="message-thread-body thin-scrollbar">
      <div v-if="!activeConversationMessages.length" class="message-thread-empty">
        <Send :size="28" />
        <span>还没有私信，先发一句吧</span>
      </div>
      <article
        v-for="message in activeConversationMessages"
        :key="message.id"
        class="message-bubble-row"
        :class="messages.messageIsMine(message) ? 'message-bubble-row--mine' : ''"
      >
        <img :src="messages.messageAvatar(message)" alt="" class="message-bubble-avatar" />
        <div class="message-bubble-stack">
          <div class="message-bubble" :class="messages.messageIsMine(message) ? 'message-bubble--mine' : ''">
            {{ message.content }}
          </div>
          <div class="message-bubble-meta">
            <time>{{ formatPublishedAt(message.createdAt) }}</time>
            <span v-if="messages.messageIsMine(message) && messages.isLastOutgoingMessage(message)" class="message-read-state">{{ message.read ? '已读' : '未读' }}</span>
          </div>
        </div>
      </article>
    </div>

    <form class="message-thread-composer" @submit.prevent="sendConversationMessage">
      <textarea
        v-model="messages.messageInput"
        maxlength="300"
        placeholder="发一条私信"
        data-testid="thread-input"
        @keydown.enter.exact.prevent="sendConversationMessage"
      ></textarea>
      <div class="flex items-center justify-between gap-3">
        <span class="text-xs font-bold text-[#718097]">{{ messages.messageInput.length }}/300</span>
        <button class="reply-submit" :disabled="!messages.messageInput.trim()" data-testid="thread-send" type="submit">
          <Send :size="15" />
          发送
        </button>
      </div>
    </form>
  </section>

  <section v-else class="glass-panel rounded-[34px] p-6">
    <p class="text-sm font-black text-[#718097]">消息和提醒</p>
    <h1 class="mt-2 text-4xl font-black tracking-normal text-[#19202f]">互动提醒</h1>

    <div class="message-tabs mt-6" :style="{ '--active-message-index': Math.max(0, messageTabs.findIndex((tab) => tab.key === noticeTab)) }">
      <span class="message-tabs__thumb"></span>
      <button
        v-for="tab in messageTabs"
        :key="tab.key"
        class="message-tabs__item"
        :class="noticeTab === tab.key ? 'text-white' : 'text-[#657086]'"
        :data-tab="tab.key"
        @click="noticeTab = tab.key"
      >
        <component :is="tab.icon" :size="17" />
        <span>{{ tab.label }}</span>
        <span v-if="messageTabUnreadCounts[tab.key]" class="message-tab-badge">{{ messageTabUnreadCounts[tab.key] }}</span>
      </button>
    </div>

    <div class="mt-5 grid gap-3">
      <article v-for="notice in activeNotices" :key="notice.id" class="notice-card">
        <button class="notice-card__main" type="button" @click="openNoticePost(notice)">
          <img :src="notice.actor.avatarUrl || fallbackAvatar(notice.actor.nickname)" alt="" class="notice-card__avatar" />
          <div class="notice-card__copy">
            <p class="notice-card__message">{{ noticeText(notice) }}</p>
          </div>
        </button>
        <button v-if="notice.post" class="notice-card__post" type="button" @click="openNoticePost(notice)">
          <img :src="notice.post.coverUrl || ''" alt="" />
          <span>{{ notice.post.title }}</span>
        </button>
        <div v-else class="notice-card__time-only">
          <time>{{ formatPublishedAt(notice.createdAt) }}</time>
        </div>
        <time v-if="notice.post" class="notice-card__time">{{ formatPublishedAt(notice.createdAt) }}</time>
      </article>
      <div v-if="!activeNotices.length" class="rounded-[24px] bg-white/58 p-5 text-center text-sm font-bold text-[#718097]">
        这里还没有新的互动
      </div>
    </div>

    <div class="mt-8">
      <div class="flex items-center justify-between">
        <h2 class="text-xl font-black text-[#19202f]">私信</h2>
        <span class="text-xs font-black text-[#718097]">{{ privateConversations.length }} 条</span>
      </div>
      <div class="mt-3 grid gap-3">
        <button
          v-for="conversation in privateConversations"
          :key="conversation.id"
          class="conversation-card"
          :data-conversation="conversation.id"
          @click="openConversation(conversation)"
        >
          <img :src="conversation.peer.avatarUrl || fallbackAvatar(conversation.peer.nickname)" alt="" class="h-12 w-12 rounded-[18px]" />
          <div class="min-w-0 flex-1">
            <div class="flex min-w-0 items-center gap-2">
              <p class="truncate font-black">{{ conversation.peer.nickname }}</p>
              <span class="presence-pill presence-pill--compact" :class="conversation.peer.online ? 'presence-pill--online' : ''">
                <span></span>
                {{ conversation.peer.online ? '在线' : '离线' }}
              </span>
            </div>
            <p class="mt-1 truncate text-sm text-[#718097]">{{ conversation.lastMessage || '打开私信给对方发消息' }}</p>
          </div>
          <span v-if="conversation.unreadCount" class="rounded-full bg-[#ff3b30] px-2 py-1 text-xs font-black text-white">{{ conversation.unreadCount }}</span>
        </button>
        <div v-if="!privateConversations.length" class="rounded-[24px] bg-white/58 p-5 text-center text-sm font-bold text-[#718097]">
          还没有私信会话
        </div>
      </div>
    </div>
  </section>
</template>