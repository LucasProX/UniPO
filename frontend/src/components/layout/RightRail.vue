<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import dayjs from 'dayjs';
import { CalendarDays } from 'lucide-vue-next';
import { useFeedStore } from '../../stores/feed';

const route = useRoute();
const feed = useFeedStore();

const props = defineProps<{
  selectedDate: string | null;
}>();

const emit = defineEmits<{
  (event: 'select-date', dateKey: string | null): void;
}>();

const activeBoard = computed(() => (route.query.board as string) || 'recommend');

function postDateKey(post: { eventDate?: string; deadlineAt?: string; publishedAt: string }) {
  return dayjs(post.eventDate || post.deadlineAt || post.publishedAt).format('YYYY-MM-DD');
}

const calendarWeekLabels = ['日', '一', '二', '三', '四', '五', '六'];
const todayKey = new Date().toISOString().slice(0, 10);

const announcementCarouselIndex = ref(0);
const announcementCarouselTransition = ref(true);
const announcementCarouselStep = 86;
let announcementCarouselTimer: number | undefined;

const operatorAnnouncements = [
  { id: 1, badge: '公告', title: '新版首页运营位正在灰度调整', summary: '右侧公告区后续会同步网站运营发布的规则、活动和功能更新。' },
  { id: 2, badge: '提醒', title: '内容反馈入口即将接入后台', summary: '举报、置顶、公告配置将逐步进入运营后台统一维护。' },
  { id: 3, badge: '规则', title: '校园 PO 发布规范本周更新', summary: '标题、图片、活动时间和联系方式将增加更清晰的审核提示。' },
  { id: 4, badge: '活动', title: '优质避坑 PO 将进入首页推荐池', summary: '被同学收藏、评论补充的信息会优先进入运营巡检列表。' },
  { id: 5, badge: '维护', title: '对象存储图片服务已切换到私有桶', summary: '站内图片会通过平台接口读取，外部不会直接暴露存储桶地址。' }
];

const announcementCarouselLoop = computed(() => {
  const list = operatorAnnouncements.map((item, index) => ({ ...item, loopKey: `${item.id}-${index}` }));
  return [...list, ...list.slice(0, 2)];
});

const calendarBaseDate = computed(() => {
  const base = props.selectedDate ? new Date(`${props.selectedDate}T00:00:00`) : new Date();
  const first = new Date(base.getFullYear(), base.getMonth(), 1);
  const offset = (first.getDay() + 6) % 7; // 周一为一周起点
  return new Date(first.getFullYear(), first.getMonth(), 1 - offset);
});

const calendarDays = computed(() => {
  const base = calendarBaseDate.value;
  return Array.from({ length: 42 }, (_, index) => {
    const date = new Date(base.getFullYear(), base.getMonth(), base.getDate() + index);
    const dateKey = date.toISOString().slice(0, 10);
    return {
      dateKey,
      day: String(date.getDate()),
      inMonth: date.getMonth() === base.getMonth(),
      postCount: feed.posts.filter((post) => postDateKey(post) === dateKey).length
    };
  });
});

onMounted(() => {
  announcementCarouselTimer = window.setInterval(() => {
    announcementCarouselTransition.value = true;
    announcementCarouselIndex.value = (announcementCarouselIndex.value + 1) % (operatorAnnouncements.length + 2);
  }, 4000);
});

onUnmounted(() => {
  if (announcementCarouselTimer) window.clearInterval(announcementCarouselTimer);
});

function selectDate(dateKey: string) {
  emit('select-date', props.selectedDate === dateKey ? null : dateKey);
}
</script>

<template>
  <aside class="home-side-rail min-h-0 space-y-4 lg:h-full lg:overflow-hidden max-[1180px]:col-span-2 max-[1180px]:grid max-[1180px]:h-auto max-[1180px]:grid-cols-2 max-[880px]:col-span-1 max-[880px]:grid-cols-1">
    <section class="glass-panel rounded-[30px] p-4">
      <div class="flex items-end justify-between gap-3">
        <div>
          <p class="text-sm font-black text-[#718097]">今日校园 PO · {{ feed.boardName(activeBoard as never) }}</p>
          <h2 class="mt-1 text-xl font-black text-[#19202f]">事件日历</h2>
        </div>
        <CalendarDays class="text-[#007aff]" :size="22" />
      </div>

      <div class="calendar-grid mt-4">
        <div v-for="label in calendarWeekLabels" :key="label" class="calendar-week-label">{{ label }}</div>
        <button
          v-for="day in calendarDays"
          :key="day.dateKey"
          class="calendar-day-button"
          :class="{
            'calendar-day-button--muted': !day.inMonth,
            'calendar-day-button--today': day.dateKey === todayKey,
            'calendar-day-button--active': selectedDate === day.dateKey,
            'calendar-day-button--has-posts': day.postCount > 0
          }"
          :data-calendar-day="day.dateKey"
          @click="selectDate(day.dateKey)"
        >
          <span>{{ day.day }}</span>
          <small v-if="day.postCount">{{ day.postCount }}</small>
        </button>
      </div>

      <div class="operator-announcements mt-4">
        <div class="flex items-center justify-between gap-3">
          <p>运营公告</p>
          <span>{{ operatorAnnouncements.length }} 条</span>
        </div>
        <div class="operator-announcements__viewport mt-2">
          <div
            class="operator-announcements__track"
            :class="announcementCarouselTransition ? '' : 'operator-announcements__track--instant'"
            :style="{ transform: `translateY(-${announcementCarouselIndex * announcementCarouselStep}px)` }"
          >
            <article v-for="notice in announcementCarouselLoop" :key="notice.loopKey" class="operator-announcement-row">
              <span class="mini-chip">{{ notice.badge }}</span>
              <div class="min-w-0">
                <strong>{{ notice.title }}</strong>
                <small>{{ notice.summary }}</small>
              </div>
            </article>
          </div>
        </div>
      </div>
    </section>

  </aside>
</template>