import { defineStore } from 'pinia';
import { ref } from 'vue';
import dayjs from 'dayjs';
import { campusApi } from '../lib/api';
import { openRealtimeStream } from '../lib/realtime';
import { useAuthStore } from './auth';
import type { ConversationView, InteractionNoticeView, MessageView } from '../types';

/** 私信会话、互动通知。Web 与 App 两端共用。 */

interface CampusMessage extends MessageView {
  optimistic?: boolean;
}

function normalizeMessage(message: MessageView): CampusMessage {
  return { ...message, read: Boolean(message.read) };
}

export const useMessagesStore = defineStore('messages', () => {
  const auth = useAuthStore();

  const conversations = ref<ConversationView[]>([]);
  const conversationMessages = ref<Record<number, CampusMessage[]>>({});
  const interactionNotices = ref<InteractionNoticeView[]>([]);
  const unreadCount = ref(0);
  const activeConversationId = ref<number | null>(null);
  const messageInput = ref('');

  let conversationListTimer: number | undefined;
  let activeConversationTimer: number | undefined;
  /** WebSocket 连接句柄与状态：连上时轮询退让（只当兜底），断开时轮询自动接管。 */
  let realtimeStop: (() => void) | undefined;
  let realtimeOpen = false;
  let refreshingConversations = false;
  let refreshingActiveConversation = false;

  const activeConversation = ref<ConversationView | null>(null);
  const activeConversationMessages = ref<CampusMessage[]>([]);

  function syncActiveConversation() {
    const id = activeConversationId.value;
    activeConversation.value = id == null ? null : conversations.value.find((item) => item.id === id) || null;
    activeConversationMessages.value = id == null ? [] : (conversationMessages.value[id] || []);
  }

  function messageIsMine(message: MessageView) {
    return message.senderId === auth.currentUser.id;
  }

  function messageAvatar(message: MessageView) {
    if (messageIsMine(message)) return auth.currentUser.avatarUrl || `https://api.dicebear.com/8.x/initials/svg?seed=${encodeURIComponent(auth.currentUser.nickname || 'PO')}`;
    return activeConversation.value?.peer.avatarUrl || `https://api.dicebear.com/8.x/initials/svg?seed=${encodeURIComponent(activeConversation.value?.peer.nickname || 'PO')}`;
  }

  function isLastOutgoingMessage(message: CampusMessage) {
    const outgoing = activeConversationMessages.value.filter(messageIsMine);
    return outgoing[outgoing.length - 1]?.id === message.id;
  }

  function appendConversationMessage(message: CampusMessage) {
    const existing = conversationMessages.value[message.conversationId] || [];
    conversationMessages.value = {
      ...conversationMessages.value,
      [message.conversationId]: [...existing, message]
    };
    syncActiveConversation();
  }

  function replaceOptimisticMessage(conversationId: number, optimisticId: number, message: CampusMessage) {
    const existing = conversationMessages.value[conversationId] || [];
    conversationMessages.value = {
      ...conversationMessages.value,
      [conversationId]: existing.map((item) => item.id === optimisticId ? message : item)
    };
    syncActiveConversation();
  }

  function removeOptimisticMessage(conversationId: number, optimisticId: number) {
    const existing = conversationMessages.value[conversationId] || [];
    conversationMessages.value = {
      ...conversationMessages.value,
      [conversationId]: existing.filter((item) => item.id !== optimisticId)
    };
    syncActiveConversation();
  }

  function mergeConversationMessages(conversationId: number, incoming: CampusMessage[]) {
    const existing = conversationMessages.value[conversationId] || [];
    const pendingOptimistic = existing.filter((message) => message.optimistic);
    const merged = new Map<number, CampusMessage>();
    existing.filter((message) => !message.optimistic).forEach((message) => merged.set(message.id, message));
    incoming.forEach((message) => merged.set(message.id, message));
    conversationMessages.value = {
      ...conversationMessages.value,
      [conversationId]: [
        ...Array.from(merged.values()),
        ...pendingOptimistic
      ].sort((a, b) => dayjs(a.createdAt).valueOf() - dayjs(b.createdAt).valueOf())
    };
    syncActiveConversation();
  }

  function updateConversationPreview(conversationId: number, lastMessage: string, createdAt = new Date().toISOString()) {
    conversations.value = conversations.value
      .map((conversation) => conversation.id === conversationId
        ? { ...conversation, lastMessage, unreadCount: 0, lastMessageAt: createdAt, updatedAt: createdAt }
        : conversation)
      .sort((a, b) => dayjs(b.lastMessageAt || b.updatedAt).valueOf() - dayjs(a.lastMessageAt || a.updatedAt).valueOf());
    syncActiveConversation();
  }

  function markConversationRead(conversationId: number) {
    const conversation = conversations.value.find((item) => item.id === conversationId);
    if (!conversation?.unreadCount) return;
    unreadCount.value = Math.max(0, unreadCount.value - conversation.unreadCount);
    conversations.value = conversations.value.map((item) => item.id === conversationId ? { ...item, unreadCount: 0 } : item);
    syncActiveConversation();
  }

  async function refreshConversations() {
    if (!auth.isAuthenticated || refreshingConversations) return;
    refreshingConversations = true;
    try {
      const [messageList, unread, notices] = await Promise.all([
        campusApi.conversations(),
        campusApi.unreadCount(),
        campusApi.interactionNotices()
      ]);
      const activeId = activeConversationId.value;
      conversations.value = messageList.map((conversation) => activeId === conversation.id ? { ...conversation, unreadCount: 0 } : conversation);
      interactionNotices.value = notices;
      unreadCount.value = activeId == null
        ? unread
        : Math.max(0, unread - (messageList.find((conversation) => conversation.id === activeId)?.unreadCount || 0));
      syncActiveConversation();
      startRealtime();
    } catch (error) {
      // 轮询遇上网关抖动时保留本地状态，不打扰用户
      if (await auth.handleAuthSensitiveError(error, '登录状态失效，请重新登录')) {
        resetAll();
      }
    } finally {
      refreshingConversations = false;
    }
  }

  /** 3 秒一次的兜底轮询：WebSocket 活着时它什么都不做（正常路径是推送）。 */
  function startConversationListPolling() {
    stopConversationListPolling();
    conversationListTimer = window.setInterval(() => {
      if (realtimeOpen) return;
      refreshConversations();
    }, 3000);
  }

  function stopConversationListPolling() {
    if (conversationListTimer) window.clearInterval(conversationListTimer);
    conversationListTimer = undefined;
  }

  async function refreshActiveConversation(options: { scroll?: boolean } = {}) {
    const conversationId = activeConversationId.value;
    if (conversationId == null || !auth.isAuthenticated || refreshingActiveConversation) return;
    refreshingActiveConversation = true;
    const currentLength = conversationMessages.value[conversationId]?.length || 0;
    try {
      const loadedMessages = await campusApi.messages(conversationId);
      mergeConversationMessages(conversationId, loadedMessages.map(normalizeMessage));
      markConversationRead(conversationId);
      await refreshConversations();
      const nextLength = conversationMessages.value[conversationId]?.length || 0;
      if (options.scroll || nextLength > currentLength) {
        await Promise.resolve();
        scrollThreadToBottom();
      }
    } catch (error) {
      if (await auth.handleAuthSensitiveError(error, '登录状态失效，请重新登录')) {
        stopActiveConversationPolling();
        resetAll();
      }
    } finally {
      refreshingActiveConversation = false;
    }
  }

  function startActiveConversationPolling() {
    stopActiveConversationPolling();
    activeConversationTimer = window.setInterval(() => {
      if (realtimeOpen) return;
      refreshActiveConversation();
    }, 1800);
  }

  function stopActiveConversationPolling() {
    if (activeConversationTimer) window.clearInterval(activeConversationTimer);
    activeConversationTimer = undefined;
  }

  /** 收到一条推送：当前会话直接拉增量，其他会话只刷新列表/未读。 */
  function handleRealtimeEvent(name: string, data: unknown) {
    if (name !== 'message') return;
    const payload = data as { conversationId?: number } | null;
    if (payload?.conversationId != null && payload.conversationId === activeConversationId.value) {
      // refreshActiveConversation 内部会顺带刷新会话列表，不必再调一次
      void refreshActiveConversation();
      return;
    }
    void refreshConversations();
  }

  function startRealtime() {
    if (realtimeStop || !auth.isAuthenticated) return;
    realtimeStop = openRealtimeStream({
      onState: (state) => { realtimeOpen = state === 'open'; },
      onEvent: handleRealtimeEvent,
    });
  }

  function stopRealtime() {
    realtimeStop?.();
    realtimeStop = undefined;
    realtimeOpen = false;
  }

  function scrollThreadToBottom() {
    const body = document.querySelector('.message-thread-body');
    body?.scrollTo({ top: body.scrollHeight, behavior: 'smooth' });
  }

  async function openConversation(conversation: ConversationView) {
    if (!auth.hasSession) {
      auth.openDialog('请先登录后查看消息');
      return;
    }
    activeConversationId.value = conversation.id;
    messageInput.value = '';
    syncActiveConversation();
    startActiveConversationPolling();
    await refreshActiveConversation({ scroll: true });
  }

  function closeConversation() {
    stopActiveConversationPolling();
    activeConversationId.value = null;
    messageInput.value = '';
    syncActiveConversation();
    refreshConversations();
  }

  async function sendMessage() {
    if (!activeConversation.value || !auth.hasSession) return;
    const content = messageInput.value.trim().slice(0, 300);
    if (!content) return;
    const conversationId = activeConversation.value.id;
    const optimistic: CampusMessage = {
      id: Date.now(),
      conversationId,
      senderId: auth.currentUser.id,
      receiverId: activeConversation.value.peer.id,
      content,
      read: false,
      createdAt: new Date().toISOString(),
      optimistic: true
    };
    appendConversationMessage(optimistic);
    messageInput.value = '';
    updateConversationPreview(conversationId, content);
    await Promise.resolve();
    scrollThreadToBottom();
    let sent: MessageView | null = null;
    try {
      sent = await campusApi.sendMessage(conversationId, content);
    } catch (error) {
      removeOptimisticMessage(conversationId, optimistic.id);
      await auth.handleAuthSensitiveError(error, '登录状态失效，私信没有发送成功', '私信发送失败，请稍后再试');
      await refreshActiveConversation({ scroll: true });
      return;
    }
    if (!sent) {
      auth.showNotice('私信发送失败，请稍后再试', 'error');
      return;
    }
    replaceOptimisticMessage(conversationId, optimistic.id, normalizeMessage(sent));
    updateConversationPreview(conversationId, content, sent.createdAt);
    await refreshActiveConversation({ scroll: true });
    await Promise.resolve();
    scrollThreadToBottom();
  }

  /** 从作者主页发起私信 */
  async function startConversationWith(profile: { publicUid: string }) {
    if (!auth.hasSession) {
      auth.openDialog('请先登录后查看消息');
      return;
    }
    let conversation: ConversationView | null = null;
    try {
      conversation = await campusApi.createConversation(profile.publicUid);
    } catch (error) {
      await auth.handleAuthSensitiveError(error, '登录状态失效，请重新登录后再试', '私信会话创建失败，请稍后再试');
      return;
    }
    if (!conversation) {
      auth.showNotice('私信会话创建失败，请稍后再试', 'error');
      return;
    }
    conversations.value = [
      conversation,
      ...conversations.value.filter((item) => item.id !== conversation.id)
    ];
    syncActiveConversation();
    await openConversation(conversation);
    return conversation;
  }

  function resetAll() {
    conversations.value = [];
    conversationMessages.value = {};
    interactionNotices.value = [];
    unreadCount.value = 0;
    activeConversationId.value = null;
    stopConversationListPolling();
    stopActiveConversationPolling();
    stopRealtime();
    syncActiveConversation();
  }

  return {
    conversations,
    conversationMessages,
    interactionNotices,
    unreadCount,
    activeConversationId,
    activeConversation,
    activeConversationMessages,
    messageInput,
    messageIsMine,
    messageAvatar,
    isLastOutgoingMessage,
    refreshConversations,
    startConversationListPolling,
    stopConversationListPolling,
    startRealtime,
    stopRealtime,
    refreshActiveConversation,
    startActiveConversationPolling,
    stopActiveConversationPolling,
    scrollThreadToBottom,
    openConversation,
    closeConversation,
    sendMessage,
    startConversationWith,
    resetAll,
    syncActiveConversation
  };
});