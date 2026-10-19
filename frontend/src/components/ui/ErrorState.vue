<script setup lang="ts">
defineProps<{
  message?: string;
}>();
</script>

<template>
  <div class="po-card p-8 text-center" role="alert">
    <p class="text-base font-black text-[#c0392b]">加载失败</p>
    <p class="mt-2 text-xs font-semibold text-[#718097]">{{ message || '请稍后再试' }}</p>
    <button class="mt-4 rounded-full bg-[#19202f] px-5 py-2 text-xs font-black text-white" @click="$emit('retry')">
      重试
    </button>
  </div>
</template>
