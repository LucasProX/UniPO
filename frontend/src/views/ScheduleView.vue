<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ChevronLeft, ChevronRight, Edit3, Upload } from 'lucide-vue-next';
import { storeToRefs } from 'pinia';
import { useScheduleStore } from '../stores/schedule';

const schedule = useScheduleStore();
const router = useRouter();
const { selectedWeek, editDraft, importNotice, scheduleDays } = storeToRefs(schedule);

const weekRange = computed(() => {
  const start = 1;
  return [start, 20];
});

function prevWeek() {
  schedule.selectedWeek = Math.max(1, schedule.selectedWeek - 1);
}

function nextWeek() {
  schedule.selectedWeek = Math.min(20, schedule.selectedWeek + 1);
}

function mockImportSchedule() {
  schedule.mockImportSchedule();
  router.push('/schedule');
}
</script>

<template>
  <section class="glass-panel rounded-[34px] p-6">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <div>
        <p class="text-sm font-black text-[#718097]">课程表</p>
        <h1 class="mt-2 text-4xl font-black tracking-normal text-[#19202f]">第 {{ selectedWeek }} 周课程</h1>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <div class="week-switcher">
          <button title="上一周" @click="prevWeek"><ChevronLeft :size="16" /></button>
          <span>第 {{ selectedWeek }} 周</span>
          <button title="下一周" @click="nextWeek"><ChevronRight :size="16" /></button>
        </div>
        <button class="inline-flex items-center gap-2 rounded-full bg-[#19202f] px-5 py-3 text-sm font-black text-white" data-testid="schedule-import" @click="mockImportSchedule">
          <Upload :size="17" />
          导入课程表
        </button>
      </div>
    </div>
    <p v-if="importNotice" class="mt-4 rounded-[18px] bg-[#eaf4ff] px-4 py-3 text-sm font-bold text-[#007aff]">{{ importNotice }}</p>

    <div class="schedule-board mt-6 thin-scrollbar">
      <div v-for="day in scheduleDays" :key="day.key" class="schedule-day">
        <div class="schedule-day__head">
          <span>{{ day.name }}</span>
          <small>{{ day.date }}</small>
        </div>
        <button
          v-for="course in schedule.coursesForDay(day.key)"
          :key="course.id"
          class="course-card"
          :style="{ '--course-color': course.color }"
          :data-course="course.id"
          @click="schedule.editCourse(course)"
        >
          <span class="flex items-center justify-between gap-2">
            <strong>{{ course.title }}</strong>
            <Edit3 :size="14" />
          </span>
          <span>{{ course.start }} - {{ course.end }}</span>
          <span>{{ course.location }} · {{ course.teacher }}</span>
          <div class="course-card__chips">
            <em>{{ course.scope }}</em>
            <em>{{ schedule.weekRangeLabel(course) }}</em>
          </div>
        </button>
      </div>
    </div>
  </section>
</template>
