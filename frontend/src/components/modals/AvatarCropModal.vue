<script setup lang="ts">
import { computed, ref } from 'vue';
import { X } from 'lucide-vue-next';
import { storeToRefs } from 'pinia';
import { useProfileStore } from '../../stores/profile';

const profile = useProfileStore();
const {
  avatarCropOpen, avatarCropImageUrl, avatarCropFrame, avatarCropScale,
  avatarCropMinScale, avatarCropMaxScale, avatarCropOffsetX, avatarCropOffsetY,
  profileAvatarUploading, profileAvatarUploadError
} = storeToRefs(profile);

const frameSize = ref(300);
const cropSize = computed(() => Math.round((avatarCropFrame.value?.clientWidth || 300) * 0.72));

const imageStyle = computed(() => ({
  width: `${avatarCropScale.value * 100}%`,
  height: `auto`,
  transform: `translate(${avatarCropOffsetX.value}px, ${avatarCropOffsetY.value}px)`
}));
</script>

<template>
  <Transition name="detail-pop">
    <div v-if="avatarCropOpen" class="fixed inset-0 z-[65] grid place-items-center bg-[#0f172a]/42 p-4 backdrop-blur-sm" data-testid="avatar-crop" @click.self="profile.cancelAvatarCrop()">
      <section class="avatar-crop-panel">
        <div class="flex items-start justify-between gap-3">
          <div>
            <p class="text-xs font-black uppercase text-[#718097]">头像裁剪</p>
            <h2 class="mt-1 text-2xl font-black text-[#19202f]">选择正方形区域</h2>
          </div>
          <button class="icon-button" aria-label="关闭" @click="profile.cancelAvatarCrop()"><X :size="18" /></button>
        </div>
        <div
          ref="avatarCropFrame"
          class="avatar-crop-frame mt-5"
          @pointerdown="profile.startAvatarCropDrag"
          @pointermove="profile.moveAvatarCropDrag"
          @pointerup="profile.endAvatarCropDrag"
          @pointercancel="profile.endAvatarCropDrag"
          @pointerleave="profile.endAvatarCropDrag"
        >
          <img v-if="avatarCropImageUrl" :src="avatarCropImageUrl" alt="" class="avatar-crop-image" :style="imageStyle" draggable="false" />
          <div class="avatar-crop-mask"></div>
          <div class="avatar-crop-box"></div>
        </div>
        <label class="avatar-crop-control mt-5">
          <span>缩放</span>
          <input v-model.number="avatarCropScale" type="range" :min="avatarCropMinScale" :max="avatarCropMaxScale" step="0.01" @input="profile.normalizeAvatarCrop()" />
        </label>
        <div class="mt-5 grid grid-cols-2 gap-3">
          <button class="avatar-crop-button avatar-crop-button--ghost" @click="profile.cancelAvatarCrop()">取消</button>
          <button class="avatar-crop-button" :disabled="profileAvatarUploading" data-testid="avatar-crop-confirm" @click="profile.confirmAvatarCrop()">
            {{ profileAvatarUploading ? '上传中...' : '确认裁剪' }}
          </button>
        </div>
        <p v-if="profileAvatarUploadError" class="mt-3 text-xs font-black text-[#ff3b30]">{{ profileAvatarUploadError }}</p>
      </section>
    </div>
  </Transition>
</template>
