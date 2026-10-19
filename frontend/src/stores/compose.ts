import { defineStore } from 'pinia';
import { ref } from 'vue';
import { apiErrorMessage, mediaApi, postApi } from '../lib/api';
import { plainPostContent } from '../lib/postHelpers';
import { sanitizeRichHtml } from '../lib/richText';
import { useAuthStore } from './auth';
import { useFeedStore } from './feed';
import type { BoardCode, PostView } from '../types';

/** 发帖编辑器（富文本 + 封面图 + 校验 + 发布）。 */

export interface ComposeDraft {
  board: Exclude<BoardCode, 'recommend'>;
  title: string;
  content: string;
  coverUrl: string;
  images: string[];
}

interface ComposeErrors {
  board: string;
  title: string;
  content: string;
}

function emptyDraft(): ComposeDraft {
  return { board: 'school', title: '', content: '', coverUrl: '', images: [] };
}

export const useComposeStore = defineStore('compose', () => {
  const auth = useAuthStore();
  const feed = useFeedStore();

  const draft = ref<ComposeDraft>(emptyDraft());
  const composeErrors = ref<ComposeErrors>({ board: '', title: '', content: '' });
  const postImagePreviewUrl = ref('');
  const publishing = ref(false);

  /** contenteditable 宿主元素；由视图挂载后注入 */
  let editorEl: HTMLElement | null = null;
  let savedRange: Range | null = null;

  function bindEditor(el: HTMLElement | null) {
    editorEl = el;
  }

  function selectBoard(board: ComposeDraft['board']) {
    draft.value.board = board;
    composeErrors.value.board = '';
  }

  function setTitle(title: string) {
    draft.value.title = title;
    if (title.trim()) composeErrors.value.title = '';
  }

  function setCoverUrl(coverUrl: string) {
    draft.value.coverUrl = coverUrl;
  }

  function syncContentFromEditor() {
    draft.value.content = editorEl?.innerHTML || '';
    if (plainPostContent(draft.value.content).trim()) composeErrors.value.content = '';
    saveSelection();
  }

  function focusEditor() {
    if (!editorEl) return;
    editorEl.focus();
    const selection = window.getSelection();
    selection?.removeAllRanges();
    if (savedRange) {
      selection?.addRange(savedRange);
    } else {
      const range = document.createRange();
      range.selectNodeContents(editorEl);
      range.collapse(false);
      selection?.addRange(range);
      savedRange = range.cloneRange();
    }
  }

  function saveSelection() {
    const selection = window.getSelection();
    if (!editorEl || !selection || selection.rangeCount === 0) return;
    const range = selection.getRangeAt(0);
    if (editorEl.contains(range.commonAncestorContainer)) savedRange = range.cloneRange();
  }

  function insertHtml(html: string) {
    focusEditor();
    document.execCommand('insertHTML', false, html);
    syncContentFromEditor();
  }

  function insertText(text: string) {
    focusEditor();
    document.execCommand('insertText', false, text);
    syncContentFromEditor();
  }

  function format(command: 'bold' | 'insertUnorderedList') {
    focusEditor();
    document.execCommand(command, false);
    syncContentFromEditor();
  }

  function applyColor(color: string) {
    focusEditor();
    document.execCommand('foreColor', false, color);
    if (editorEl) normalizeEditorMarkup(editorEl);
    syncContentFromEditor();
  }

  function applyFontSize(size: 'large' | 'small') {
    focusEditor();
    document.execCommand('fontSize', false, size === 'large' ? '5' : '2');
    if (editorEl) normalizeEditorMarkup(editorEl);
    syncContentFromEditor();
  }

  function handlePaste(event: ClipboardEvent) {
    event.preventDefault();
    insertText(event.clipboardData?.getData('text/plain') || '');
  }

  function resetEditor() {
    if (editorEl) editorEl.innerHTML = '';
    draft.value = emptyDraft();
    composeErrors.value = { board: '', title: '', content: '' };
    clearImagePreview();
  }

  function clearImagePreview() {
    if (postImagePreviewUrl.value) URL.revokeObjectURL(postImagePreviewUrl.value);
    postImagePreviewUrl.value = '';
  }

  function validate() {
    syncContentFromEditor();
    const errors: ComposeErrors = {
      board: draft.value.board ? '' : '请选择发布范围',
      title: draft.value.title.trim() ? '' : '请填写标题',
      content: plainPostContent(draft.value.content).trim() ? '' : '请填写正文内容'
    };
    composeErrors.value = errors;
    return !errors.board && !errors.title && !errors.content;
  }

  async function uploadCover(file: File) {
    if (!auth.isAuthenticated) {
      auth.openDialog('请先登录后上传图片');
      return;
    }
    const previous = draft.value.coverUrl;
    clearImagePreview();
    postImagePreviewUrl.value = URL.createObjectURL(file);
    draft.value.coverUrl = postImagePreviewUrl.value;
    try {
      const uploaded = await mediaApi.upload(file, 'post');
      draft.value.coverUrl = uploaded.url;
      clearImagePreview();
    } catch (error) {
      draft.value.coverUrl = previous;
      clearImagePreview();
      auth.showNotice(apiErrorMessage(error, '图片上传失败，请确认图片大小和对象存储配置'), 'error');
    }
  }

  async function insertInlineImage(file: File) {
    if (!auth.isAuthenticated) {
      auth.openDialog('请先登录后插入图片');
      return;
    }
    try {
      const uploaded = await mediaApi.upload(file, 'post');
      insertHtml(`<div><img src="${uploaded.url}" alt="插入图片" /></div><div><br></div>`);
    } catch (error) {
      auth.showNotice(apiErrorMessage(error, '图片上传失败，请确认图片大小和对象存储配置'), 'error');
    }
  }

  /** 发布；成功后清空草稿并回到首页 */
  async function publish() {
    if (!auth.hasSession) {
      auth.openDialog('请先登录后发布 PO');
      return;
    }
    if (!validate()) return;
    publishing.value = true;
    const payload = {
      board: draft.value.board,
      title: draft.value.title.trim(),
      content: sanitizeRichHtml(draft.value.content.trim()),
      coverUrl: draft.value.coverUrl.trim() || undefined,
      images: draft.value.images,
      tags: ['新PO', feed.boardName(draft.value.board)]
    };
    let apiPost: PostView | null = null;
    try {
      apiPost = await postApi.create(payload);
    } catch (error) {
      publishing.value = false;
      if (await auth.handleAuthSensitiveError(error, '登录状态失效，PO 没有写入后端', '发布失败，请稍后再试')) return;
      return;
    } finally {
      publishing.value = false;
    }
    if (!apiPost) {
      auth.openDialog('登录状态失效，PO 没有写入后端');
      return;
    }
    const post = feed.addLocalPost(apiPost);
    auth.userStats.posts += 1;
    feed.selectedPost = post;
    resetEditor();
  }

  return {
    draft,
    composeErrors,
    postImagePreviewUrl,
    publishing,
    bindEditor,
    selectBoard,
    setTitle,
    setCoverUrl,
    syncContentFromEditor,
    focusEditor,
    saveSelection,
    insertHtml,
    insertText,
    format,
    applyColor,
    applyFontSize,
    handlePaste,
    resetEditor,
    clearImagePreview,
    validate,
    uploadCover,
    insertInlineImage,
    publish
  };
});

/** 把 execCommand 产生的 <font> 归一成 <span>，与 sanitizeRichHtml 的白名单对齐 */
export function normalizeEditorMarkup(root: HTMLElement) {
  root.querySelectorAll('font').forEach((node) => {
    const span = document.createElement('span');
    const size = node.getAttribute('size');
    const color = node.getAttribute('color');
    if (size) span.classList.add(Number(size) >= 4 ? 'rich-post-content__large' : 'rich-post-content__small');
    if (color && /^(#[0-9a-fA-F]{3,8}|rgb\(\s*\d{1,3}\s*,\s*\d{1,3}\s*,\s*\d{1,3}\s*\))$/.test(color.trim())) {
      span.style.color = color.trim();
    }
    span.innerHTML = node.innerHTML;
    node.replaceWith(span);
  });
}
