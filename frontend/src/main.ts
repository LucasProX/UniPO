import { nextTick, createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import './styles.css';

createApp(App).use(createPinia()).mount('#root');
nextTick(() => {
  document.getElementById('root')?.setAttribute('data-mounted', 'true');
});
