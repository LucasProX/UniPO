<script setup lang="ts">
defineProps<{
  text?: string;
}>();
</script>

<template>
  <div class="po-card p-8 text-center text-sm font-bold text-[#718097]" role="status" aria-live="polite">
    {{ text || '正在加载…' }}
  </div>
</template>
