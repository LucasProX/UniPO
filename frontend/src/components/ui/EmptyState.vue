<script setup lang="ts">
defineProps<{
  text?: string;
}>();
</script>

<template>
  <div class="po-card p-8 text-center text-sm font-bold text-[#718097]" role="status">
    {{ text || '这里还没有内容' }}
  </div>
</template>
