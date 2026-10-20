import { nextTick, createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import router from './router';
import './styles.css';

const app = createApp(App);

/**
 * 全局兜底：组件渲染/生命周期里没被 catch 的异常。
 *
 * 现在只打日志（带组件名与 lifecycle 信息），不弹 toast——
 * 局部错误应该由视图自己的 error 状态处理，全局兜底是最后一道保险。
 * 接 Sentry 时在这里上报，别在每个组件里各写一份 try/catch。
 */
app.config.errorHandler = (error, instance, info) => {
  console.error('[unipo] unhandled component error', { info, component: instance?.$options?.name }, error);
};

// 未处理的 Promise（比如事件回调里忘了 await）同样收口，避免只出现在浏览器控制台却没人知道。
window.addEventListener('unhandledrejection', (event) => {
  console.error('[unipo] unhandled rejection', event.reason);
});

app.use(createPinia()).use(router).mount('#root');
nextTick(() => {
  document.getElementById('root')?.setAttribute('data-mounted', 'true');
});
