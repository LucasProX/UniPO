<script setup lang="ts">
import { LogIn, LogOut, UserRound, X } from 'lucide-vue-next';
import { useRouter } from 'vue-router';
import { useAuthStore } from '../../stores/auth';

const auth = useAuthStore();
const router = useRouter();

defineProps<{
  open: boolean;
}>();

const emit = defineEmits<{
  (event: 'close'): void;
}>();

function openAccountCenter() {
  emit('close');
  if (!auth.hasSession) {
    auth.openDialog('请先登录后进入账号中心');
    return;
  }
  router.push('/profile');
}

function openDrawerLogin() {
  emit('close');
  auth.openDialog();
}

function logout() {
  emit('close');
  auth.logout();
}
</script>

<template>
  <Transition name="drawer-fade">
    <div v-if="open" class="fixed inset-0 z-[62] bg-[#0f172a]/28 backdrop-blur-sm" data-testid="account-drawer" @click.self="emit('close')">
      <aside class="account-drawer">
        <div class="flex items-start justify-between gap-3">
          <div class="min-w-0">
            <p class="text-xs font-black uppercase text-[#718097]">菜单</p>
            <h2 class="mt-1 text-2xl font-black text-[#19202f]">{{ auth.isAuthenticated ? auth.currentUser.nickname : '未登录' }}</h2>
            <p class="mt-1 truncate text-sm font-bold text-[#718097]">{{ auth.isAuthenticated ? `UID ${auth.currentUser.publicUid}` : '登录后可使用账号功能' }}</p>
          </div>
          <button class="icon-button" aria-label="关闭" @click="emit('close')"><X :size="18" /></button>
        </div>

        <div class="mt-6 grid gap-3">
          <button class="drawer-action" data-testid="drawer-account" @click="openAccountCenter">
            <UserRound :size="20" />
            <span>
              <strong>账号中心</strong>
              <small>资料、安全和绑定入口</small>
            </span>
          </button>
          <button v-if="auth.isAuthenticated" class="drawer-action drawer-action--danger" data-testid="drawer-logout" @click="logout">
            <LogOut :size="20" />
            <span>
              <strong>退出登录</strong>
              <small>注销当前会话并回到首页</small>
            </span>
          </button>
          <button v-else class="drawer-action" data-testid="drawer-login" @click="openDrawerLogin">
            <LogIn :size="20" />
            <span>
              <strong>登录</strong>
              <small>进入你的校园账号</small>
            </span>
          </button>
        </div>
      </aside>
    </div>
  </Transition>
</template>
