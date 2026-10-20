<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { X } from 'lucide-vue-next';
import { campusApi } from '../../lib/api';
import { fallbackAvatar } from '../../lib/postHelpers';
import { formatCount } from '../../lib/format';
import type { FollowUserView } from '../../types';

/**
 * 粉丝 / 关注列表弹窗。
 *
 * 两种视角：
 * - 自己的主页：关注列表可「取消关注」，粉丝列表可「移除粉丝」；
 * - 别人的主页：每行只显示「关注 / 已关注」。
 *
 * 列表项用 FollowUserView（后端刻意不返回完整资料），不带 email。
 */
const props = defineProps<{
  open: boolean;
  uid: string;
  mine: boolean;
  initialTab?: 'followers' | 'following';
}>();

const emit = defineEmits<{
  (event: 'close'): void;
  (event: 'updated', payload: { followersDelta: number; followingDelta: number }): void;
}>();

const tab = ref<'followers' | 'following'>('followers');
const followers = ref<FollowUserView[]>([]);
const following = ref<FollowUserView[]>([]);
const loading = ref(false);
const error = ref('');
const busyUid = ref<string | null>(null);
const loadedKey = ref('');

const rows = computed(() => (tab.value === 'followers' ? followers.value : following.value));

async function load() {
  if (!props.open || !props.uid) return;
  const key = `${props.uid}:${props.initialTab || 'followers'}`;
  if (loadedKey.value === key) return;
  loading.value = true;
  error.value = '';
  tab.value = props.initialTab || 'followers';
  try {
    const [fans, friends] = await Promise.all([
      campusApi.followers(props.uid),
      campusApi.following(props.uid)
    ]);
    followers.value = fans;
    following.value = friends;
    loadedKey.value = key;
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

watch(() => [props.open, props.uid, props.initialTab], () => {
  if (props.open) void load();
  else loadedKey.value = '';
}, { immediate: true });

async function unfollowRow(row: FollowUserView) {
  busyUid.value = row.publicUid;
  try {
    if (props.mine && tab.value === 'following') {
      await campusApi.unfollow(row.publicUid);
      following.value = following.value.filter((item) => item.publicUid !== row.publicUid);
      emit('updated', { followersDelta: 0, followingDelta: -1 });
    } else {
      await campusApi.unfollow(row.publicUid);
      row.following = false;
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '操作失败';
  } finally {
    busyUid.value = null;
  }
}

async function followRow(row: FollowUserView) {
  busyUid.value = row.publicUid;
  try {
    await campusApi.follow(row.publicUid);
    row.following = true;
  } catch (e) {
    error.value = e instanceof Error ? e.message : '操作失败';
  } finally {
    busyUid.value = null;
  }
}

async function removeFan(row: FollowUserView) {
  if (!window.confirm(`确定移除粉丝「${row.nickname}」吗？`)) return;
  busyUid.value = row.publicUid;
  try {
    await campusApi.removeFollower(row.publicUid);
    followers.value = followers.value.filter((item) => item.publicUid !== row.publicUid);
    emit('updated', { followersDelta: -1, followingDelta: 0 });
  } catch (e) {
    error.value = e instanceof Error ? e.message : '操作失败';
  } finally {
    busyUid.value = null;
  }
}

function close() {
  emit('close');
}
</script>

<template>
  <div v-if="open" class="fixed inset-0 z-50 grid place-items-center bg-slate-950/40 p-4" @click.self="close">
    <div class="flex max-h-[78vh] w-full max-w-lg flex-col overflow-hidden rounded-[26px] bg-white shadow-2xl">
      <header class="flex items-center justify-between border-b border-slate-100 px-5 py-4">
        <div class="flex gap-1 rounded-full bg-slate-100 p-1">
          <button
            class="rounded-full px-4 py-1.5 text-sm font-bold transition"
            :class="tab === 'followers' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'"
            @click="tab = 'followers'"
          >
            粉丝 {{ formatCount(followers.length) }}
          </button>
          <button
            class="rounded-full px-4 py-1.5 text-sm font-bold transition"
            :class="tab === 'following' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'"
            @click="tab = 'following'"
          >
            关注 {{ formatCount(following.length) }}
          </button>
        </div>
        <button class="grid h-8 w-8 place-items-center rounded-full text-slate-400 hover:bg-slate-100" aria-label="关闭" @click="close">
          <X :size="18" />
        </button>
      </header>

      <div v-if="error" class="mx-5 mt-3 rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-700">{{ error }}</div>

      <div class="min-h-[220px] flex-1 overflow-y-auto px-2 py-2">
        <div v-if="loading" class="grid h-full place-items-center py-10 text-sm text-slate-400">加载中…</div>
        <div v-else-if="!rows.length" class="grid h-full place-items-center py-10 text-sm text-slate-400">
          {{ tab === 'followers' ? '还没有粉丝' : '还没有关注任何人' }}
        </div>
        <ul v-else class="divide-y divide-slate-100">
          <li v-for="row in rows" :key="row.publicUid" class="flex items-center gap-3 px-3 py-3">
            <RouterLink :to="`/u/${row.publicUid}`" class="shrink-0" @click="close">
              <img :src="row.avatarUrl || fallbackAvatar(row.nickname)" alt="" class="h-11 w-11 rounded-[14px] object-cover" />
            </RouterLink>
            <div class="min-w-0 flex-1">
              <RouterLink :to="`/u/${row.publicUid}`" class="block truncate text-sm font-bold text-slate-900 hover:underline" @click="close">
                {{ row.nickname }}
              </RouterLink>
              <p class="mt-0.5 truncate text-xs text-slate-400">
                Lv.{{ row.level }}<template v-if="row.college"> · {{ row.college }}</template><template v-if="row.major"> · {{ row.major }}</template>
              </p>
            </div>

            <button
              v-if="mine && tab === 'followers'"
              class="shrink-0 rounded-full border border-slate-200 px-3 py-1 text-xs font-bold text-slate-500 hover:border-rose-200 hover:text-rose-600"
              :disabled="busyUid === row.publicUid"
              @click="removeFan(row)"
            >
              移除粉丝
            </button>
            <button
              v-else-if="mine && tab === 'following'"
              class="shrink-0 rounded-full border border-slate-200 px-3 py-1 text-xs font-bold text-slate-500 hover:border-rose-200 hover:text-rose-600"
              :disabled="busyUid === row.publicUid"
              @click="unfollowRow(row)"
            >
              取消关注
            </button>
            <button
              v-else-if="row.following"
              class="shrink-0 rounded-full border border-slate-200 px-3 py-1 text-xs font-bold text-slate-500"
              :disabled="busyUid === row.publicUid"
              @click="unfollowRow(row)"
            >
              已关注
            </button>
            <button
              v-else
              class="shrink-0 rounded-full bg-[#19202f] px-3 py-1 text-xs font-bold text-white"
              :disabled="busyUid === row.publicUid"
              @click="followRow(row)"
            >
              关注
            </button>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>
