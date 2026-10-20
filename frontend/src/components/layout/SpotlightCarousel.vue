<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { ChevronLeft, ChevronRight } from 'lucide-vue-next';
import type { SpotlightView } from '../../types';

/**
 * 首页大屏轮播（运营 / 算法共用一套数据结构）。
 *
 * - 数据来自 GET /api/spotlights；空数组时父组件不渲染本组件（这里也做了兜底）。
 * - imageUrl 为空时用品牌渐变 + 文案，不依赖外链图片（国内网络下外链经常白屏）。
 * - 自动轮播 6s，鼠标悬停/聚焦时暂停；支持左右箭头与圆点。
 */
const props = defineProps<{ items: SpotlightView[] }>();
const emit = defineEmits<{ (event: 'open', linkUrl: string): void }>();

const index = ref(0);
const paused = ref(false);
let timer: number | undefined;

const current = computed(() => props.items[index.value % Math.max(1, props.items.length)]);
const canRotate = computed(() => props.items.length > 1);

function go(delta: number) {
  if (!props.items.length) return;
  index.value = (index.value + delta + props.items.length) % props.items.length;
}

function open() {
  const link = current.value?.linkUrl;
  if (link) emit('open', link);
}

function start() {
  stop();
  if (!canRotate.value) return;
  timer = window.setInterval(() => {
    if (!paused.value) go(1);
  }, 6000);
}

function stop() {
  if (timer) window.clearInterval(timer);
  timer = undefined;
}

onMounted(start);
onBeforeUnmount(stop);
</script>

<template>
  <section
    v-if="current"
    class="spotlight"
    data-testid="home-spotlight"
    @mouseenter="paused = true"
    @mouseleave="paused = false"
    @focusin="paused = true"
    @focusout="paused = false"
  >
    <div class="spotlight__frame" @click="open">
      <div
        class="spotlight__media"
        :class="{ 'spotlight__media--fallback': !current.imageUrl }"
        :style="current.imageUrl ? { backgroundImage: `url(${current.imageUrl})` } : undefined"
      />
      <div class="spotlight__shade" />
      <div class="spotlight__content">
        <span v-if="current.badge" class="spotlight__badge">{{ current.badge }}</span>
        <h2 class="spotlight__title">{{ current.title }}</h2>
        <p v-if="current.subtitle" class="spotlight__subtitle">{{ current.subtitle }}</p>
        <span v-if="current.linkUrl" class="spotlight__cta">查看详情</span>
      </div>

      <template v-if="canRotate">
        <button class="spotlight__arrow spotlight__arrow--left" aria-label="上一张" @click.stop="go(-1)">
          <ChevronLeft :size="20" />
        </button>
        <button class="spotlight__arrow spotlight__arrow--right" aria-label="下一张" @click.stop="go(1)">
          <ChevronRight :size="20" />
        </button>
      </template>
    </div>

    <div v-if="canRotate" class="spotlight__dots">
      <button
        v-for="(item, i) in items"
        :key="item.id"
        class="spotlight__dot"
        :class="{ 'spotlight__dot--active': i === index }"
        :aria-label="`第 ${i + 1} 张`"
        @click="index = i"
      />
    </div>
  </section>
</template>

<style scoped>
.spotlight {
  margin-bottom: 20px;
}

.spotlight__frame {
  position: relative;
  overflow: hidden;
  border-radius: 18px;
  min-height: 210px;
  cursor: pointer;
  isolation: isolate;
  box-shadow: 0 18px 40px -24px rgba(15, 23, 42, 0.55);
}

.spotlight__media {
  position: absolute;
  inset: 0;
  background-size: cover;
  background-position: center;
  transform: scale(1.02);
  transition: transform 6s ease-out;
}

.spotlight__frame:hover .spotlight__media {
  transform: scale(1.06);
}

.spotlight__media--fallback {
  background:
    radial-gradient(1200px 400px at 15% 20%, rgba(96, 165, 250, 0.55), transparent 60%),
    radial-gradient(900px 380px at 85% 80%, rgba(168, 85, 247, 0.45), transparent 60%),
    linear-gradient(135deg, #0f172a 0%, #1e3a8a 55%, #312e81 100%);
}

.spotlight__shade {
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, rgba(2, 6, 23, 0.82) 0%, rgba(2, 6, 23, 0.45) 55%, rgba(2, 6, 23, 0.15) 100%);
}

.spotlight__content {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-width: 620px;
  padding: 32px 36px;
  color: #f8fafc;
}

.spotlight__badge {
  align-self: flex-start;
  padding: 3px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.16);
  border: 1px solid rgba(255, 255, 255, 0.28);
  font-size: 12px;
  letter-spacing: 1px;
}

.spotlight__title {
  margin: 0;
  font-size: clamp(22px, 2.4vw, 30px);
  font-weight: 700;
  line-height: 1.3;
}

.spotlight__subtitle {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: rgba(226, 232, 240, 0.9);
}

.spotlight__cta {
  margin-top: 6px;
  font-size: 13px;
  color: #93c5fd;
}

.spotlight__arrow {
  position: absolute;
  top: 50%;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.28);
  background: rgba(15, 23, 42, 0.35);
  color: #e2e8f0;
  transform: translateY(-50%);
  opacity: 0;
  transition: opacity 160ms ease, background 160ms ease;
}

.spotlight__frame:hover .spotlight__arrow,
.spotlight__arrow:focus-visible {
  opacity: 1;
}

.spotlight__arrow:hover {
  background: rgba(15, 23, 42, 0.6);
}

.spotlight__arrow--left {
  left: 14px;
}

.spotlight__arrow--right {
  right: 14px;
}

.spotlight__dots {
  display: flex;
  justify-content: center;
  gap: 6px;
  margin-top: 10px;
}

.spotlight__dot {
  width: 18px;
  height: 4px;
  border-radius: 999px;
  border: 0;
  background: rgba(100, 116, 139, 0.35);
  transition: background 160ms ease, width 160ms ease;
}

.spotlight__dot--active {
  width: 28px;
  background: #3b82f6;
}

@media (max-width: 880px) {
  .spotlight__content {
    padding: 22px 20px;
  }

  .spotlight__frame {
    min-height: 180px;
  }
}
</style>
