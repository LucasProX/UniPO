/**
 * 富文本清洗与渲染。**零 Vue 依赖**，Web 与 App 两端共用。
 *
 * 安全边界：sanitizeRichHtml 用白名单（B/STRONG/I/EM/U/BR/DIV/P/SPAN/UL/OL/LI/IMG）
 * 过滤所有标签与属性，IMG 只保留 src/alt，SPAN 只保留 class（两个字号类）与 color。
 * 新增富文本展示必须走这里，不要直接 v-html 未清洗内容。
 */

/** 把 execCommand 产生的 <font> 归一成 <span>，与白名单对齐 */
export function normalizeRichEditorMarkup(root: HTMLElement | DocumentFragment) {
  root.querySelectorAll('font').forEach((node) => {
    const span = document.createElement('span');
    const size = node.getAttribute('size');
    const color = node.getAttribute('color');
    if (size) {
      span.classList.add(Number(size) >= 4 ? 'rich-post-content__large' : 'rich-post-content__small');
    }
    if (color && /^(#[0-9a-fA-F]{3,8}|rgb\(\s*\d{1,3}\s*,\s*\d{1,3}\s*,\s*\d{1,3}\s*\))$/.test(color.trim())) {
      span.style.color = color.trim();
    }
    span.innerHTML = node.innerHTML;
    node.replaceWith(span);
  });
}

const ALLOWED_TAGS = new Set(['B', 'STRONG', 'I', 'EM', 'U', 'BR', 'DIV', 'P', 'SPAN', 'UL', 'OL', 'LI', 'IMG']);
const ALLOWED_SPAN_CLASSES = ['rich-post-content__large', 'rich-post-content__small'];

export function sanitizeRichHtml(content: string) {
  const template = document.createElement('template');
  template.innerHTML = content;
  normalizeRichEditorMarkup(template.content);
  template.content.querySelectorAll('*').forEach((node) => {
    if (!ALLOWED_TAGS.has(node.tagName)) {
      node.replaceWith(document.createTextNode(node.textContent || ''));
      return;
    }
    Array.from(node.attributes).forEach((attr) => {
      const name = attr.name.toLowerCase();
      if (node.tagName === 'IMG' && name === 'src') return;
      if (node.tagName === 'IMG' && name === 'alt') return;
      if (node.tagName === 'SPAN' && name === 'class' && ALLOWED_SPAN_CLASSES.includes(attr.value)) return;
      if (node.tagName === 'SPAN' && name === 'style') {
        const color = attr.value.match(/(?:^|;)\s*color:\s*(#[0-9a-fA-F]{3,8}|rgb\(\s*\d{1,3}\s*,\s*\d{1,3}\s*,\s*\d{1,3}\s*\))/i)?.[1];
        if (color) {
          node.setAttribute('style', `color: ${color}`);
          return;
        }
      }
      node.removeAttribute(attr.name);
    });
  });
  return template.innerHTML;
}

function escapeHtml(content: string) {
  return content
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** 纯文本里的 **粗体** / [size=] / [color=] 标记转成 HTML */
export function renderInlineRichText(content: string) {
  return escapeHtml(content)
    .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
    .replace(/\[size=large](.*?)\[\/size]/g, '<span class="rich-post-content__large">$1</span>')
    .replace(/\[size=small](.*?)\[\/size]/g, '<span class="rich-post-content__small">$1</span>')
    .replace(/\[color=(#[0-9a-fA-F]{3,8})](.*?)\[\/color]/g, '<span style="color: $1">$2</span>');
}

/** 富文本或旧标记文本 -> 可安全 v-html 的字符串 */
export function renderRichPostContent(content: string) {
  if (/<[a-z][\s\S]*>/i.test(content)) return sanitizeRichHtml(content);
  return content
    .split(/!\[[^\]]*]\([^)]+\)/g)
    .map((part) => renderInlineRichText(part))
    .join('')
    .replace(/\n/g, '<br>');
}
