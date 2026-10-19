<script setup lang="ts">
import { Upload, X } from 'lucide-vue-next';
import { storeToRefs } from 'pinia';
import { useAuthStore } from '../../stores/auth';
import { useProfileStore } from '../../stores/profile';
import { fallbackAvatar, levelTitleFor } from '../../lib/postHelpers';

const auth = useAuthStore();
const profile = useProfileStore();
const { profileDraft, profileAvatarPreviewUrl, profileAvatarUploading, profileAvatarUploadError, profileNicknameChecking, profileNicknameError } = storeToRefs(profile);

function onFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (file) void profile.handleAvatarFile(file);
}
</script>

<template>
  <Transition name="detail-pop">
    <div v-if="profileDraft" class="fixed inset-0 z-50 grid place-items-center bg-[#0f172a]/34 p-4 backdrop-blur-sm" data-testid="profile-editor" @click.self="profile.closeEditor()">
      <section class="w-[min(560px,calc(100vw-32px))] rounded-[30px] bg-white p-6 shadow-[0_30px_120px_rgba(15,23,42,.28)]">
        <div class="flex items-center justify-between">
          <h2 class="text-2xl font-black text-[#19202f]">编辑资料</h2>
          <button class="icon-button" aria-label="关闭" @click="profile.closeEditor()"><X :size="18" /></button>
        </div>

        <div class="mt-6 flex items-center gap-4">
          <label class="group relative block h-24 w-24 shrink-0 cursor-pointer overflow-hidden rounded-[28px] border-4 border-[#f0f4fa] bg-[#f6f8fb] shadow-lg">
            <img :src="profileAvatarPreviewUrl || profileDraft.avatarUrl || fallbackAvatar(profileDraft.nickname)" alt="" class="h-full w-full object-cover transition group-hover:scale-105" />
            <span class="absolute inset-0 grid place-items-center bg-[#19202f]/34 text-white opacity-0 transition group-hover:opacity-100">
              <Upload :size="22" />
            </span>
            <span v-if="profileAvatarUploading" class="absolute inset-x-0 bottom-0 bg-[#19202f]/72 py-1 text-center text-[11px] font-black text-white">上传中</span>
            <input class="sr-only" type="file" accept="image/*" @change="onFile" />
          </label>
          <div class="min-w-0">
            <p class="truncate text-lg font-black text-[#19202f]">{{ profileDraft.nickname || 'Lucas同学' }}</p>
            <p class="mt-1 text-sm font-bold text-[#718097]">UID {{ profileDraft.publicUid }} · {{ levelTitleFor(auth.currentUser.level) }}</p>
            <p v-if="profileAvatarUploadError" class="mt-2 text-xs font-black text-[#ff3b30]">{{ profileAvatarUploadError }}</p>
          </div>
        </div>

        <div class="mt-5 grid gap-3">
          <label class="grid gap-2">
            <span class="text-xs font-black text-[#718097]">名称</span>
            <input v-model="profileDraft.nickname" class="field" :class="{ 'field--error': Boolean(profileNicknameError) }" maxlength="24" placeholder="输入你的名称" />
            <span v-if="profileNicknameError" class="text-xs font-black text-[#ff3b30]">{{ profileNicknameError }}</span>
          </label>
          <label class="grid gap-2">
            <span class="text-xs font-black text-[#718097]">简介</span>
            <textarea v-model="profileDraft.bio" class="field min-h-24 resize-y" maxlength="120" placeholder="写一句个人简介"></textarea>
          </label>
          <label class="grid gap-2">
            <span class="text-xs font-black text-[#718097]">座右铭</span>
            <input v-model="profileDraft.motto" class="field" maxlength="30" placeholder="30 字以内的座右铭" />
          </label>
          <button
            class="rounded-full bg-[#19202f] px-5 py-3 text-sm font-black text-white disabled:cursor-not-allowed disabled:opacity-60"
            :disabled="profileAvatarUploading || profileNicknameChecking"
            data-testid="profile-save"
            @click="profile.save()"
          >
            {{ profileAvatarUploading ? '头像上传中...' : profileNicknameChecking ? '校验中...' : '保存资料' }}
          </button>
        </div>
      </section>
    </div>
  </Transition>
</template>
