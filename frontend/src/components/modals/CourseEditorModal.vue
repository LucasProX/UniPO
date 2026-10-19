<script setup lang="ts">
import { X } from 'lucide-vue-next';
import { storeToRefs } from 'pinia';
import { useScheduleStore } from '../../stores/schedule';

const schedule = useScheduleStore();
const { editDraft } = storeToRefs(schedule);

function close() {
  schedule.closeEditor();
}
</script>

<template>
  <Transition name="detail-pop">
    <div v-if="editDraft" class="fixed inset-0 z-50 grid place-items-center bg-[#0f172a]/34 p-4 backdrop-blur-sm" data-testid="course-editor" @click.self="close">
      <section class="w-[min(560px,calc(100vw-32px))] rounded-[30px] bg-white p-6 shadow-[0_30px_120px_rgba(15,23,42,.28)]">
        <div class="flex items-center justify-between">
          <h2 class="text-2xl font-black text-[#19202f]">编辑课程</h2>
          <button class="icon-button" aria-label="关闭" @click="close"><X :size="18" /></button>
        </div>
        <div class="mt-5 grid gap-3">
          <input v-model="editDraft.title" class="field" placeholder="课程名" />
          <div class="grid grid-cols-2 gap-3">
            <input v-model="editDraft.start" class="field" placeholder="开始时间" />
            <input v-model="editDraft.end" class="field" placeholder="结束时间" />
          </div>
          <div class="grid grid-cols-2 gap-3">
            <label class="grid gap-2">
              <span class="text-xs font-black text-[#718097]">起始周</span>
              <input v-model.number="editDraft.startWeek" class="field" min="1" :max="20" type="number" />
            </label>
            <label class="grid gap-2">
              <span class="text-xs font-black text-[#718097]">结束周</span>
              <input v-model.number="editDraft.endWeek" class="field" min="1" :max="20" type="number" />
            </label>
          </div>
          <input v-model="editDraft.location" class="field" placeholder="教室" />
          <input v-model="editDraft.teacher" class="field" placeholder="老师" />
          <button class="rounded-full bg-[#19202f] px-5 py-3 text-sm font-black text-white" data-testid="course-save" @click="schedule.saveCourse()">保存课程</button>
        </div>
      </section>
    </div>
  </Transition>
</template>
