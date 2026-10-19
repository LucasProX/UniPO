import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import dayjs from 'dayjs';
import type { Course } from '../types';

/**
 * 课程表。
 *
 * ⚠️ 当前没有后端接口，课程只存在浏览器 localStorage（key: 'bcg_courses'）。
 * 换设备/清缓存会丢。等后端提供 /api/schedule 后，把 load/persist 换成接口调用即可，
 * 视图层不用改。
 */

const STORAGE_KEY = 'bcg_courses_v1';
export const TERM_START = dayjs('2026-02-16');
export const MAX_TERM_WEEK = 20;
const WEEK_NAMES = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];

function clampWeek(value: number) {
  return Math.min(MAX_TERM_WEEK, Math.max(1, Math.round(value)));
}

function seedCourse(id: number, day: number, title: string, start: string, end: string, location: string, teacher: string, color: string, scope: string, startWeek: number, endWeek: number): Course {
  return { id, day, title, start, end, location, teacher, color, scope, startWeek, endWeek, seriesId: id };
}

/** 首次使用的示例课表；localStorage 有数据后不再写入 */
function defaultCourses(): Course[] {
  return [
    seedCourse(1, 1, '高等数学 A', '08:00', '09:40', '教三 301', '周老师', '#007aff', '必修', 1, 16),
    seedCourse(2, 1, '大学英语', '10:00', '11:40', '外语楼 204', 'Lisa', '#ff9500', '必修', 1, 16),
    seedCourse(3, 2, '数据结构', '08:00', '09:40', '计算机楼 105', '陈老师', '#34c759', '核心', 1, 16),
    seedCourse(4, 2, '线性代数', '14:00', '15:40', '教二 208', '吴老师', '#af52de', '必修', 1, 16),
    seedCourse(5, 3, '高等数学 A', '08:00', '09:40', '教三 301', '周老师', '#007aff', '必修', 1, 16),
    seedCourse(6, 3, '计算机组成原理', '14:00', '15:40', '计算机楼 302', '郑老师', '#ff2d55', '核心', 1, 16),
    seedCourse(7, 4, '数据结构实验', '08:00', '11:40', '实验楼 501', '陈老师', '#34c759', '实验', 2, 15),
    seedCourse(8, 4, '大学物理', '14:00', '15:40', '教一 大礼堂', '孙老师', '#5856d6', '必修', 1, 16),
    seedCourse(9, 5, '体育（篮球）', '10:00', '11:40', '东体育场', '马老师', '#ffcc00', '必修', 1, 16),
    seedCourse(10, 5, '思想政治', '14:00', '15:40', '教一 101', '李老师', '#8e8e93', '必修', 1, 16)
  ];
}

function loadCourses(): Course[] {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (!stored) return defaultCourses();
    const parsed = JSON.parse(stored);
    if (!Array.isArray(parsed) || !parsed.length) return defaultCourses();
    return parsed.filter((course): course is Course => Boolean(course && typeof course.id === 'number'));
  } catch {
    return defaultCourses();
  }
}

function persist(courses: Course[]) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(courses));
  } catch {
    // 隐私模式/超额时静默失败，仅本次会话有效
  }
}

export const useScheduleStore = defineStore('schedule', () => {
  const courses = ref<Course[]>(loadCourses());
  const selectedWeek = ref(clampWeek(Math.max(1, dayjs().diff(TERM_START, 'week') + 1)));
  const editDraft = ref<Course | null>(null);
  const importNotice = ref(false);
  let courseIdSeed = 0;
  let importNoticeTimer: number | undefined;

  const scheduleDays = computed(() => Array.from({ length: 7 }, (_, index) => {
    const date = TERM_START.add(selectedWeek.value - 1, 'week').add(index, 'day');
    return { key: index + 1, name: WEEK_NAMES[index], date: date.format('M/D') };
  }));

  function coursesForDay(day: number) {
    return courses.value
      .filter((course) => course.day === day && course.startWeek <= selectedWeek.value && course.endWeek >= selectedWeek.value)
      .sort((a, b) => a.start.localeCompare(b.start));
  }

  function nextCourseId() {
    const nextId = Math.max(courseIdSeed, ...courses.value.map((course) => course.id)) + 1;
    courseIdSeed = nextId;
    return nextId;
  }

  function normalizeCourseWeeks(course: Course) {
    const startWeek = clampWeek(Number(course.startWeek) || selectedWeek.value);
    const endWeek = clampWeek(Number(course.endWeek) || startWeek);
    return {
      ...course,
      startWeek: Math.min(startWeek, endWeek),
      endWeek: Math.max(startWeek, endWeek)
    };
  }

  function weekRangeLabel(course: Course) {
    return course.startWeek === course.endWeek ? `第 ${course.startWeek} 周` : `${course.startWeek}-${course.endWeek} 周`;
  }

  /** 打开编辑弹窗；course 为空表示新建 */
  function editCourse(course?: Course) {
    if (course) {
      editDraft.value = { ...course };
      return;
    }
    editDraft.value = {
      id: nextCourseId(),
      day: 1,
      title: '',
      start: '08:00',
      end: '09:40',
      location: '',
      teacher: '',
      color: '#007aff',
      scope: '选修',
      startWeek: selectedWeek.value,
      endWeek: selectedWeek.value,
      seriesId: nextCourseId()
    };
  }

  function closeEditor() {
    editDraft.value = null;
  }

  /**
   * 保存课程。修改了起止周次时，会把原课程按周次拆成多段（保留未改部分）。
   */
  function saveCourse() {
    if (!editDraft.value) return;
    const sourceCourse = courses.value.find((course) => course.id === editDraft.value?.id);
    const draftCourse = normalizeCourseWeeks(editDraft.value);
    const nextCourses = courses.value.filter((course) => course.id !== draftCourse.id);

    if (sourceCourse) {
      if (sourceCourse.startWeek < draftCourse.startWeek) {
        nextCourses.push({ ...sourceCourse, id: nextCourseId(), endWeek: draftCourse.startWeek - 1 });
      }
      if (sourceCourse.endWeek > draftCourse.endWeek) {
        nextCourses.push({ ...sourceCourse, id: nextCourseId(), startWeek: draftCourse.endWeek + 1 });
      }
    }

    nextCourses.push(draftCourse);
    courses.value = nextCourses.sort((a, b) => a.seriesId - b.seriesId || a.startWeek - b.startWeek || a.day - b.day || a.start.localeCompare(b.start));
    editDraft.value = null;
    persist(courses.value);
  }

  function deleteCourse(id: number) {
    courses.value = courses.value.filter((course) => course.id !== id);
    if (editDraft.value?.id === id) editDraft.value = null;
    persist(courses.value);
  }

  /** 「导入课程表」目前是本地占位（后端没有接口） */
  function mockImportSchedule() {
    importNotice.value = true;
    if (importNoticeTimer) window.clearTimeout(importNoticeTimer);
    importNoticeTimer = window.setTimeout(() => { importNotice.value = false; }, 3200);
  }

  return {
    courses,
    selectedWeek,
    editDraft,
    importNotice,
    scheduleDays,
    coursesForDay,
    weekRangeLabel,
    editCourse,
    closeEditor,
    saveCourse,
    deleteCourse,
    mockImportSchedule
  };
});
