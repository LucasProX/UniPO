<script setup lang="ts">
import { computed, watch } from 'vue';
import { Bookmark, Heart, MessageCircle, Reply, Send, X } from 'lucide-vue-next';
import { useFeedStore } from '../../stores/feed';
import { fallbackAvatar, formatPublishedAt, levelTitleFor, roleLabel } from '../../lib/postHelpers';
import { renderRichPostContent } from '../../lib/richText';
import type { CampusComment, CampusPost } from '../../types';

const feed = useFeedStore();

const emit = defineEmits<{
  (event: 'open-author', author: CampusPost['author']): void;
}>();

const post = computed(() => feed.expandedPost);

// 打开/切换帖子时同步评论区派生数据
watch(post, (next) => {
  if (next) feed.syncDetailComments();
}, { immediate: true });

watch(() => feed.comments.length, () => feed.syncDetailComments());

function close() {
  feed.closePost();
}
</script>

<template>
  <Transition name="detail-pop">
    <div v-if="post" class="fixed inset-0 z-50 grid place-items-center bg-[#0f172a]/38 p-4 backdrop-blur-sm" data-testid="post-detail" @click.self="close">
      <article class="detail-panel thin-scrollbar">
        <button class="absolute right-5 top-5 z-10 grid h-10 w-10 place-items-center rounded-full bg-white/90 text-[#19202f]" aria-label="关闭" @click="close">
          <X :size="19" />
        </button>
        <div class="p-5 sm:p-7">
          <div class="flex items-start justify-between gap-4 pr-12">
            <div class="flex min-w-0 items-center gap-3">
              <button class="author-tap" @click="emit('open-author', post.author)">
                <img :src="post.author.avatarUrl || fallbackAvatar(post.author.nickname)" alt="" class="h-14 w-14 rounded-[18px] object-cover" />
              </button>
              <div class="min-w-0">
                <p class="truncate text-base font-black text-[#19202f]">{{ post.author.nickname }}</p>
                <p class="mt-1 truncate text-xs font-semibold text-[#718097]">Lv.{{ post.author.level }} {{ levelTitleFor(post.author.level) }} · {{ roleLabel(post) }}</p>
              </div>
            </div>
            <span class="mini-chip">{{ feed.boardName(post.board) }}</span>
          </div>

          <h1 class="mt-6 text-[clamp(30px,5vw,48px)] font-black leading-tight tracking-normal text-[#19202f]">{{ post.title }}</h1>
          <div class="rich-post-content mt-4" v-html="renderRichPostContent(post.content)"></div>

          <div class="mt-5 flex flex-wrap gap-2">
            <span v-for="tag in post.tags" :key="tag" class="mini-chip">{{ tag }}</span>
          </div>
          <time class="mt-3 block text-right text-sm font-black text-[#19202f]">{{ formatPublishedAt(post.publishedAt) }}</time>

          <div class="mt-6 grid grid-cols-3 gap-3">
            <button class="action-metric action-metric--like" :class="post.liked ? 'action-metric--active' : ''" data-testid="detail-like" @click="feed.toggleLike(post)">
              <Heart :size="28" :fill="post.liked ? 'currentColor' : 'none'" />
              <strong>{{ post.likeCount }}</strong>
              <span>点赞</span>
            </button>
            <button class="action-metric action-metric--comment" data-testid="detail-comment-toggle" @click="feed.toggleCommentBox()">
              <MessageCircle :size="28" fill="currentColor" />
              <strong>{{ post.commentCount }}</strong>
              <span>评论</span>
            </button>
            <button class="action-metric action-metric--favorite" :class="post.favorited ? 'action-metric--active' : ''" data-testid="detail-favorite" @click="feed.toggleFavorite(post)">
              <Bookmark :size="28" :fill="post.favorited ? 'currentColor' : 'none'" />
              <strong>{{ post.favoriteCount }}</strong>
              <span>收藏</span>
            </button>
          </div>

          <div v-if="feed.showPostCommentBox" class="reply-box mt-4">
            <textarea v-model="feed.newCommentText" maxlength="200" placeholder="写一条评论，最多 200 字"></textarea>
            <div class="mt-2 flex items-center justify-between">
              <span class="text-xs font-bold text-[#718097]">{{ feed.newCommentText.length }}/200</span>
              <button class="reply-submit" data-testid="detail-comment-submit" @click="feed.submitComment()">
                <Send :size="15" />
                回复
              </button>
            </div>
          </div>

          <div class="mt-6">
            <h2 class="text-xl font-black text-[#19202f]">评论区</h2>
            <div class="mt-3 space-y-3">
              <article v-for="comment in feed.rootDetailComments" :key="comment.id" class="comment-card">
                <div class="flex items-center gap-2">
                  <button class="author-tap" @click="emit('open-author', comment.author)">
                    <img :src="comment.author.avatarUrl || fallbackAvatar(comment.author.nickname)" alt="" class="h-8 w-8 rounded-full" />
                  </button>
                  <p class="text-sm font-black">{{ comment.author.nickname }}</p>
                  <span class="text-xs font-bold text-[#718097]">Lv.{{ comment.author.level }} {{ levelTitleFor(comment.author.level) }}</span>
                </div>
                <p class="mt-2 text-sm leading-6 text-[#596579]">{{ comment.content }}</p>
                <div class="comment-footer">
                  <div class="flex items-center gap-2">
                    <button class="comment-action" :class="comment.liked ? 'comment-action--active' : ''" @click="feed.toggleCommentLike(comment)">
                      <Heart :size="14" :fill="comment.liked ? 'currentColor' : 'none'" />
                      {{ comment.likeCount }}
                    </button>
                    <button class="comment-action" @click="feed.startReply(comment)">
                      <Reply :size="14" />
                      回复
                    </button>
                  </div>
                  <time>{{ formatPublishedAt(comment.createdAt) }}</time>
                </div>

                <div v-if="feed.replyTargetId === comment.id" class="reply-box reply-box--compact mt-3">
                  <textarea v-model="feed.replyText" maxlength="200" :placeholder="`回复 ${comment.author.nickname}`"></textarea>
                  <div class="mt-2 flex items-center justify-between">
                    <span class="text-xs font-bold text-[#718097]">{{ feed.replyText.length }}/200</span>
                    <button class="reply-submit" @click="feed.submitReply(comment)">
                      <Send :size="15" />
                      回复
                    </button>
                  </div>
                </div>

                <div v-if="feed.visibleReplies(comment).length" class="reply-list">
                  <article v-for="reply in feed.visibleReplies(comment)" :key="reply.id" class="reply-bubble">
                    <div class="flex items-center gap-2">
                      <button class="author-tap" @click="emit('open-author', reply.author)">
                        <img :src="reply.author.avatarUrl || fallbackAvatar(reply.author.nickname)" alt="" class="h-6 w-6 rounded-full" />
                      </button>
                      <p class="text-xs font-black">{{ reply.author.nickname }}</p>
                      <span class="text-[11px] font-bold text-[#718097]">Lv.{{ reply.author.level }} {{ levelTitleFor(reply.author.level) }}</span>
                    </div>
                    <p class="mt-1 text-sm leading-6 text-[#596579]">{{ reply.content }}</p>
                    <div class="comment-footer">
                      <div class="flex items-center gap-2">
                        <button class="comment-action" :class="reply.liked ? 'comment-action--active' : ''" @click="feed.toggleCommentLike(reply)">
                          <Heart :size="13" :fill="reply.liked ? 'currentColor' : 'none'" />
                          {{ reply.likeCount }}
                        </button>
                      </div>
                      <time>{{ formatPublishedAt(reply.createdAt) }}</time>
                    </div>
                  </article>
                </div>

                <button
                  v-if="feed.remainingReplyCount(comment) > 0 || (feed.repliesFor(comment).length > 0 && !feed.expandedReplyCount(comment))"
                  class="expand-replies"
                  @click="feed.expandReplies(comment)"
                >
                  {{ feed.expandedReplyCount(comment) ? `继续展开 ${feed.remainingReplyCount(comment)} 条` : `展开回复 ${feed.repliesFor(comment).length} 条` }}
                </button>
              </article>
            </div>
          </div>
        </div>
      </article>
    </div>
  </Transition>
</template>
