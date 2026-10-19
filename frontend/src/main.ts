import { nextTick, createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import router from './router';
import './styles.css';

createApp(App).use(createPinia()).use(router).mount('#root');
nextTick(() => {
  document.getElementById('root')?.setAttribute('data-mounted', 'true');
});