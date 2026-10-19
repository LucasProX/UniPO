<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Bold, ImagePlus, ListPlus, Palette, Type, Upload } from 'lucide-vue-next';
import { useComposeStore } from '../stores/compose';
import { useAuthStore } from '../stores/auth';
import type { BoardCode } from '../types';

const compose = useComposeStore();
const auth = useAuthStore();
const router = useRouter();

const editor = ref<HTMLElement | null>(null);

const boardOptions: Array<{ value: Exclude<BoardCode, 'recommend'>; label: string; description: string }> = [
  { value: 'school', label: '校园', description: '校园生活、通知、活动' },
  { value: 'college', label: '学院', description: '各学院课程、实验、考试经验' },
  { value: 'major', label: '专业', description: '专业课、选课、学业互助' }
];

const emojis = ['😀', '😂', '🥰', '👍', '🙏', '🔥', '💯', '😭', '🤔', '🎉'];
const colors = [
  { value: '#19202f', label: '墨黑' },
  { value: '#007aff', label: '品牌蓝' },
  { value: '#ff3b30', label: '警示红' },
  { value: '#34c759', label: '通过绿' },
  { value: '#ff9500', label: '提示橙' }
];

const composeBoardIndex = computed(() => Math.max(0, boardOptions.findIndex((option) => option.value === compose.draft.board)));

const contentLength = computed(() => compose.draft.content.replace(/<[^>]*>/g, '').length);
const inlineImageCount = computed(() => (compose.draft.content.match(/<img/gi) || []).length);

onMounted(() => {
  compose.bindEditor(editor.value);
  if (!auth.hasSession) auth.openDialog('请先登录后发布校园 PO');
});

onUnmounted(() => {
  compose.bindEditor(null);
});

function onCoverFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (file) void compose.uploadCover(file);
}

function onInlineImage(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (file) void compose.insertInlineImage(file);
}

async function publish() {
  await compose.publish();
  if (!compose.composeErrors.board && !compose.composeErrors.title && !compose.composeErrors.content) {
    router.push('/');
  }
}
</script>

<template>
  <section class="glass-panel rounded-[34px] p-6">
    <p class="text-sm font-black text-[#718097]">登录后发布校园 PO</p>
    <h1 class="mt-2 text-4xl font-black tracking-normal text-[#19202f]">发一条校园 PO</h1>
    <div class="mt-6 grid gap-4">
      <div
        class="compose-board-selector"
        :class="compose.composeErrors.board ? 'compose-board-selector--error' : ''"
        :style="{ '--compose-board-index': composeBoardIndex }"
        role="radiogroup"
        aria-label="选择发布范围"
      >
        <span class="compose-board-selector__thumb"></span>
        <button
          v-for="option in boardOptions"
          :key="option.value"
          type="button"
          class="compose-board-option"
          :class="compose.draft.board === option.value ? 'compose-board-option--active' : ''"
          role="radio"
          :aria-checked="compose.draft.board === option.value"
          :data-board="option.value"
          @click="compose.selectBoard(option.value)"
        >
          <span class="compose-board-option__icon">
          </span>
          <span class="min-w-0">
            <strong>{{ option.label }}</strong>
            <small>{{ option.description }}</small>
          </span>
        </button>
      </div>
      <p v-if="compose.composeErrors.board" class="compose-field-error">{{ compose.composeErrors.board }}</p>
      <input
        v-model="compose.draft.title"
        class="field"
        :class="compose.composeErrors.title ? 'field--error' : ''"
        placeholder="标题，比如：数据结构实验报告别只写代码"
        data-testid="compose-title"
        @input="compose.composeErrors.title = ''"
      />
      <p v-if="compose.composeErrors.title" class="compose-field-error">{{ compose.composeErrors.title }}</p>
      <section class="compose-editor" :class="compose.composeErrors.content ? 'compose-editor--error' : ''">
        <div class="compose-editor__toolbar" aria-label="正文工具栏">
          <div class="compose-editor__group">
            <button type="button" class="compose-editor__tool" title="加粗" @click="compose.format('bold')">
              <Bold :size="17" />
            </button>
            <button type="button" class="compose-editor__tool compose-editor__tool--text" title="大字号" @click="compose.applyFontSize('large')">
              <Type :size="18" />
              <span>大</span>
            </button>
            <button type="button" class="compose-editor__tool compose-editor__tool--text" title="小字号" @click="compose.applyFontSize('small')">
              <Type :size="14" />
              <span>小</span>
            </button>
            <button type="button" class="compose-editor__tool" title="插入清单" @click="compose.format('insertUnorderedList')">
              <ListPlus :size="17" />
            </button>
          </div>

          <div class="compose-editor__group compose-editor__group--emojis" aria-label="常用表情">
            <button
              v-for="emoji in emojis"
              :key="emoji"
              type="button"
              class="compose-editor__emoji"
              :title="`插入 ${emoji}`"
              @click="compose.insertText(emoji)"
            >
              {{ emoji }}
            </button>
          </div>

          <div class="compose-editor__group compose-editor__group--colors" aria-label="文字颜色">
            <Palette :size="16" />
            <button
              v-for="color in colors"
              :key="color.value"
              type="button"
              class="compose-editor__swatch"
              :style="{ '--swatch-color': color.value }"
              :title="color.label"
              @click="compose.applyColor(color.value)"
            ></button>
          </div>

          <label class="compose-editor__tool" title="插入图片" @pointerdown.capture="compose.saveSelection">
            <ImagePlus :size="17" />
            <input class="sr-only" type="file" accept="image/*" @click="compose.saveSelection" @change="onInlineImage" />
          </label>
        </div>
        <div
          ref="editor"
          class="compose-editor__body compose-editor__body--rich"
          contenteditable="true"
          role="textbox"
          aria-multiline="true"
          data-placeholder="写清楚发生了什么、适合谁、下一步怎么做"
          data-testid="compose-editor"
          @input="compose.syncContentFromEditor()"
          @keyup="compose.saveSelection"
          @mouseup="compose.saveSelection"
          @blur="compose.syncContentFromEditor()"
          @focus="compose.saveSelection"
          @paste="compose.handlePaste"
        ></div>
        <div class="compose-editor__footer">
          <span>{{ contentLength }}/2000</span>
          <span v-if="inlineImageCount">已插入 {{ inlineImageCount }} 张图</span>
        </div>
      </section>
      <p v-if="compose.composeErrors.content" class="compose-field-error">{{ compose.composeErrors.content }}</p>
      <section class="compose-cover-card">
        <div v-if="compose.draft.coverUrl" class="compose-cover-card__preview">
          <img :src="compose.draft.coverUrl" alt="" />
          <span>封面预览</span>
        </div>
        <div class="compose-cover-card__actions">
          <div>
            <p>封面图片</p>
            <span>{{ compose.draft.coverUrl ? '已上传，可以随时更换' : '用于首页卡片和通知缩略图' }}</span>
          </div>
          <label class="compose-cover-card__button">
            <Upload :size="17" />
            {{ compose.draft.coverUrl ? '更换封面' : '上传封面图片' }}
            <input class="sr-only" type="file" accept="image/*" @change="onCoverFile" />
          </label>
        </div>
      </section>
      <button class="rounded-full bg-[#19202f] px-5 py-3 text-sm font-black text-white disabled:opacity-60" :disabled="compose.publishing" data-testid="compose-publish" @click="publish">
        {{ compose.publishing ? '发布中...' : '发布 PO' }}
      </button>
    </div>
  </section>
</template>