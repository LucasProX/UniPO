<script setup lang="ts">
import { computed } from 'vue';
import { X } from 'lucide-vue-next';
import { useAuthStore } from '../../stores/auth';
import { levelTitleFor } from '../../lib/postHelpers';

const auth = useAuthStore();

defineProps<{
  open: boolean;
}>();

const emit = defineEmits<{
  (event: 'close'): void;
}>();

const MAX_LEVEL = 100;

/** 与 postHelpers.LEVEL_TIERS 对应：每 10 级一个阶段 */
const catalog = [
  { min: 1, max: 10, title: '萌新报到', summary: '刚入学的新同学', description: '先逛逛校园 PO，熟悉课程、食堂和社团信息。' },
  { min: 11, max: 20, title: '校园熟客', summary: '已经有点经验', description: '能快速找到教学楼、自习室和活动报名入口。' },
  { min: 21, max: 30, title: '信息猎手', summary: '会主动找一手信息', description: '知道去运营公告和学长评论里挖真实答案。' },
  { min: 31, max: 40, title: '活跃分子', summary: '日常打卡型用户', description: '签到、评论、收藏都有稳定产出。' },
  { min: 41, max: 50, title: '校园达人', summary: '同学眼里的活地图', description: '写的避坑 PO 常被收藏和引用。' },
  { min: 51, max: 60, title: '内容创作者', summary: '稳定产出优质内容', description: '内容会进入运营巡检和首页推荐池。' },
  { min: 61, max: 70, title: '意见领袖', summary: '说话有分量', description: '你的经验会影响一批同学的判断。' },
  { min: 71, max: 80, title: '校园传奇', summary: '标志性用户', description: '几乎覆盖校园生活的所有场景。' },
  { min: 81, max: 90, title: '资深校友', summary: '全领域都有涉猎', description: '新功能会优先邀请你参与内测。' },
  { min: 91, max: 100, title: '校园之光', summary: '最高阶段铭牌', description: '平台层面的校园建设者。' }
];

const levelCatalog = computed(() =>
  catalog.map((item, index) => {
    const active = auth.currentUser.level >= item.min && auth.currentUser.level <= item.max;
    return {
      key: `${item.min}-${item.max}`,
      rangeLabel: `Lv.${item.min}-${item.max}`,
      title: item.title,
      summary: item.summary,
      description: item.description,
      className: `level-nameplate--tier-${index + 1}`,
      active
    };
  })
);

const remainingXp = computed(() => {
  if (auth.currentUser.level >= MAX_LEVEL) return 0;
  return Math.max(0, (auth.currentUser.level + 1) * 520 - auth.currentUser.xp);
});
</script>

<template>
  <Transition name="detail-pop">
    <div v-if="open" class="fixed inset-0 z-[55] grid place-items-center bg-[#0f172a]/34 p-4 backdrop-blur-sm" data-testid="level-catalog" @click.self="emit('close')">
      <section class="level-catalog-modal bg-white p-6 shadow-[0_30px_120px_rgba(15,23,42,.28)]">
        <div class="flex items-start justify-between gap-4">
          <div>
            <h2 class="text-2xl font-black text-[#19202f]">等级铭牌</h2>
            <p class="mt-1 text-sm font-bold text-[#718097]">每 10 级一个阶段铭牌</p>
          </div>
          <button class="icon-button" aria-label="关闭" @click="emit('close')"><X :size="18" /></button>
        </div>

        <div class="level-catalog-current">
          <button class="level-nameplate" :class="`level-nameplate--tier-${Math.min(10, Math.max(1, Math.ceil(auth.currentUser.level / 10)))}`">
            <strong>Lv.{{ auth.currentUser.level }}</strong>
            <span>{{ levelTitleFor(auth.currentUser.level) }}</span>
          </button>
          <div>
            <p class="text-sm font-black text-[#19202f]">Lv.{{ auth.currentUser.level }} {{ levelTitleFor(auth.currentUser.level) }}</p>
            <p class="mt-1 text-xs font-bold text-[#718097]">距离下一级还需要 {{ remainingXp }} XP</p>
          </div>
        </div>

        <div class="level-catalog-table thin-scrollbar">
          <article v-for="item in levelCatalog" :key="item.key" class="level-catalog-row" :class="item.active ? 'level-catalog-row--current' : ''">
            <button class="level-nameplate level-nameplate--catalog" :class="item.className">
              <strong>{{ item.rangeLabel }}</strong>
              <span>{{ item.title }}</span>
            </button>
            <div>
              <strong>{{ item.active ? '当前阶段' : item.summary }}</strong>
              <small>{{ item.description }}</small>
            </div>
          </article>
        </div>
      </section>
    </div>
  </Transition>
</template>
