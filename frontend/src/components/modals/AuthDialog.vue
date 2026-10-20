<script setup lang="ts">
import { ChevronLeft, X } from 'lucide-vue-next';
import { storeToRefs } from 'pinia';
import { useAuthStore } from '../../stores/auth';

const auth = useAuthStore();
const {
  dialogOpen, authMode, authSubmitting, authForms, authErrors,
  verificationCodeSending, registerCodeCountdown, resetCodeCountdown
} = storeToRefs(auth);

function close() {
  auth.closeDialog();
}
</script>

<template>
  <div v-if="dialogOpen" class="fixed inset-0 z-[65] grid place-items-center bg-[#0f172a]/34 p-4 backdrop-blur-sm" data-testid="auth-dialog" @click.self="close">
    <section class="auth-shell" :class="authMode !== 'login' ? 'auth-shell--subview' : ''">
      <div class="auth-shell__grid">
        <aside class="auth-shell__intro">
          <img src="/auth-campus.svg" alt="" class="auth-shell__intro-media" />
          <div class="auth-shell__intro-content">
            <div class="auth-shell__brand flex items-center gap-3">
              <img src="/favicon.svg" alt="" class="h-14 w-14 rounded-[18px]" />
              <h2 class="text-3xl font-black tracking-normal text-white">UniPO</h2>
            </div>
            <p class="auth-shell__intro-copy max-w-[24rem] text-sm leading-7 text-white/86">
              在这里，你可以享受和同好交友的快乐，接收到所有大学期间的重要信息，打破和同龄人的信息差。
            </p>
          </div>
        </aside>

        <div class="auth-shell__panel" :class="authMode !== 'login' ? 'auth-shell__panel--flipped' : ''">
          <div class="auth-shell__panel-inner">
            <section class="auth-face auth-face--front">
              <button class="auth-close-button icon-button" type="button" aria-label="关闭" @click="close"><X :size="18" /></button>
              <div class="auth-face__header auth-face__header--front">
                <div>
                  <h3 class="auth-face__title">登录</h3>
                  <p class="auth-face__subtitle">欢迎回来</p>
                </div>
              </div>

              <form class="mt-6 grid gap-4" @submit.prevent="auth.login()">
                <label class="auth-field">
                  <span>邮箱 / 账号</span>
                  <input
                    v-model="authForms.login.email"
                    class="field auth-field__input"
                    :class="authErrors.login.email ? 'field--error' : ''"
                    placeholder="请输入邮箱或账号"
                    type="text"
                    autocomplete="username"
                    data-testid="login-email"
                  />
                </label>
                <label class="auth-field">
                  <span>密码</span>
                  <input
                    v-model="authForms.login.password"
                    class="field auth-field__input"
                    :class="authErrors.login.password ? 'field--error' : ''"
                    placeholder="请输入密码"
                    type="password"
                    autocomplete="current-password"
                    data-testid="login-password"
                  />
                </label>
                <button class="auth-primary-button" :disabled="authSubmitting" type="submit" data-testid="login-submit">
                  <span v-if="!authSubmitting">登录</span>
                  <span v-else class="auth-loading-dot" aria-label="登录中"></span>
                </button>
              </form>

              <div class="mt-5 flex items-center justify-between gap-3 text-sm">
                <button class="auth-link" type="button" @click="auth.switchMode('register')">注册账号</button>
                <button class="auth-link" type="button" @click="auth.switchMode('reset')">忘记密码</button>
              </div>
            </section>

            <section class="auth-face auth-face--back">
              <button class="auth-back-button icon-button" type="button" title="返回登录" @click="auth.switchMode('login')">
                <ChevronLeft :size="18" />
              </button>
              <div class="auth-face__right-actions">
                <div class="auth-tab-row" :class="authMode === 'reset' ? 'auth-tab-row--reset' : ''">
                  <button class="auth-tab" :class="authMode === 'register' ? 'auth-tab--active' : ''" type="button" @click="auth.switchMode('register')">注册账号</button>
                  <button class="auth-tab" :class="authMode === 'reset' ? 'auth-tab--active' : ''" type="button" @click="auth.switchMode('reset')">找回密码</button>
                </div>
                <button class="auth-close-button auth-close-button--inline icon-button" type="button" aria-label="关闭" @click="close"><X :size="18" /></button>
              </div>

              <div class="auth-face__header auth-face__header--back" aria-hidden="true"></div>

              <div class="auth-form-slider" :class="authMode === 'reset' ? 'auth-form-slider--reset' : ''">
                <div class="auth-form-track">
                  <form
                    class="auth-form-page grid gap-3"
                    :class="authMode === 'register' ? 'auth-form-page--active' : ''"
                    :aria-hidden="authMode !== 'register'"
                    :inert="authMode !== 'register'"
                    @submit.prevent="auth.register()"
                  >
                    <label class="auth-field">
                      <span>用户名</span>
                      <input
                        v-model="authForms.register.nickname"
                        class="field auth-field__input"
                        :class="authErrors.register.nickname ? 'field--error' : ''"
                        placeholder="选填，默认与账号相同"
                        type="text"
                        autocomplete="nickname"
                        data-testid="register-nickname"
                      />
                    </label>
                    <label class="auth-field">
                      <span>账号</span>
                      <div class="auth-split-row">
                        <input
                          v-model="authForms.register.email"
                          class="field auth-field__input"
                          :class="authErrors.register.email ? 'field--error' : ''"
                          placeholder="任意字符串，不重名即可"
                          type="text"
                          autocomplete="email"
                          data-testid="register-email"
                        />
                        <button class="auth-code-button" type="button" :disabled="verificationCodeSending || registerCodeCountdown > 0" @click="auth.sendVerificationCode('register')">
                          <span v-if="registerCodeCountdown > 0">{{ registerCodeCountdown }}s</span>
                          <span v-else-if="!verificationCodeSending">发送验证码</span>
                          <span v-else class="auth-loading-dot auth-loading-dot--sm" aria-label="发送中"></span>
                        </button>
                      </div>
                    </label>
                    <label class="auth-field">
                      <span>验证码</span>
                      <input
                        v-model="authForms.register.verificationCode"
                        class="field auth-field__input"
                        :class="authErrors.register.verificationCode ? 'field--error' : ''"
                        placeholder="4 位数字，开发环境随便填"
                        inputmode="numeric"
                        maxlength="4"
                        type="text"
                        autocomplete="one-time-code"
                        data-testid="register-code"
                      />
                    </label>
                    <label class="auth-field">
                      <span>密码</span>
                      <input
                        v-model="authForms.register.password"
                        class="field auth-field__input"
                        :class="authErrors.register.password ? 'field--error' : ''"
                        placeholder="开发环境随意，上限 72 字节"
                        type="password"
                        autocomplete="new-password"
                        data-testid="register-password"
                      />
                    </label>
                    <label class="auth-field">
                      <span>确认密码</span>
                      <input
                        v-model="authForms.register.confirmPassword"
                        class="field auth-field__input"
                        :class="authErrors.register.confirmPassword ? 'field--error' : ''"
                        placeholder="再次输入密码"
                        type="password"
                        autocomplete="new-password"
                      />
                    </label>
                    <button class="auth-primary-button" :disabled="authSubmitting" type="submit" data-testid="register-submit">
                      <span v-if="!authSubmitting">注册并登录</span>
                      <span v-else class="auth-loading-dot" aria-label="提交中"></span>
                    </button>
                  </form>

                  <form
                    class="auth-form-page grid gap-3"
                    :class="authMode === 'reset' ? 'auth-form-page--active' : ''"
                    :aria-hidden="authMode !== 'reset'"
                    :inert="authMode !== 'reset'"
                    @submit.prevent="auth.resetPassword()"
                  >
                    <label class="auth-field">
                      <span>绑定邮箱</span>
                      <div class="auth-split-row">
                        <input
                          v-model="authForms.reset.email"
                          class="field auth-field__input"
                          :class="authErrors.reset.email ? 'field--error' : ''"
                          placeholder="请输入邮箱"
                          type="email"
                          autocomplete="email"
                          data-testid="reset-email"
                        />
                        <button class="auth-code-button" type="button" :disabled="verificationCodeSending || resetCodeCountdown > 0" @click="auth.sendVerificationCode('reset')">
                          <span v-if="resetCodeCountdown > 0">{{ resetCodeCountdown }}s</span>
                          <span v-else-if="!verificationCodeSending">发送验证码</span>
                          <span v-else class="auth-loading-dot auth-loading-dot--sm" aria-label="发送中"></span>
                        </button>
                      </div>
                    </label>
                    <label class="auth-field">
                      <span>验证码</span>
                      <input
                        v-model="authForms.reset.verificationCode"
                        class="field auth-field__input"
                        :class="authErrors.reset.verificationCode ? 'field--error' : ''"
                        placeholder="请输入 4 位验证码"
                        inputmode="numeric"
                        maxlength="4"
                        type="text"
                        autocomplete="one-time-code"
                      />
                    </label>
                    <label class="auth-field">
                      <span>新密码</span>
                      <input
                        v-model="authForms.reset.password"
                        class="field auth-field__input"
                        :class="authErrors.reset.password ? 'field--error' : ''"
                        placeholder="至少 6 位"
                        type="password"
                        autocomplete="new-password"
                      />
                    </label>
                    <label class="auth-field">
                      <span>确认新密码</span>
                      <input
                        v-model="authForms.reset.confirmPassword"
                        class="field auth-field__input"
                        :class="authErrors.reset.confirmPassword ? 'field--error' : ''"
                        placeholder="再次输入新密码"
                        type="password"
                        autocomplete="new-password"
                      />
                    </label>
                    <button class="auth-primary-button" :disabled="authSubmitting" type="submit">
                      <span v-if="!authSubmitting">重置密码</span>
                      <span v-else class="auth-loading-dot" aria-label="提交中"></span>
                    </button>
                  </form>
                </div>
              </div>
            </section>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>
