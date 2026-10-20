import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { apiErrorMessage, authApi, authRefreshToken, campusApi, clearAuthToken, hasAuthToken, isAuthError, saveAuthToken } from '../lib/api';
import { describeError } from '../lib/postHelpers';
import type { UserProfile, UserStats } from '../types';
import { useFeedStore } from './feed';
import { useMessagesStore } from './messages';
import { useProfileStore } from './profile';
import { useScheduleStore } from './schedule';

/** 登录态、账号资料、打卡。Web 与 App 两端共用。 */

export type AuthModeKey = 'login' | 'register' | 'reset';
export type AuthFieldKey = 'email' | 'password' | 'nickname' | 'verificationCode' | 'confirmPassword';

interface LoginForm { email: string; password: string }
interface RegisterForm { nickname: string; email: string; verificationCode: string; password: string; confirmPassword: string }
interface ResetForm { email: string; verificationCode: string; password: string; confirmPassword: string }

export function guestUser(): UserProfile {
  return {
    id: 0,
    publicUid: '00000000',
    email: '',
    nickname: '访客',
    verifiedStatus: 'unverified',
    role: 'USER',
    level: 1,
    xp: 0,
    levelTitle: '星途引路人',
    status: 'active'
  };
}

export function emptyStats(): UserStats {
  return {
    favorites: 0,
    likedPosts: 0,
    sharedPosts: 0,
    completed: 0,
    comments: 0,
    upcoming: 0,
    avoidedRisks: 0,
    following: 0,
    followers: 0,
    posts: 0
  };
}

export const useAuthStore = defineStore('auth', () => {
  const currentUser = ref<UserProfile>(guestUser());
  const userStats = ref<UserStats>(emptyStats());
  const isAuthenticated = ref(false);

  // ---- UI 反馈（toast / 登录弹窗）----
  const notice = ref('');
  const noticeTone = ref<'neutral' | 'error'>('neutral');
  let noticeTimer: number | undefined;
  const dialogOpen = ref(false);

  // ---- 表单 ----
  const authMode = ref<AuthModeKey>('login');
  const authSubmitting = ref(false);
  const verificationCodeSending = ref(false);
  const registerCodeCountdown = ref(0);
  const resetCodeCountdown = ref(0);
  let registerCodeCountdownTimer: number | undefined;
  let resetCodeCountdownTimer: number | undefined;
  let nicknameAvailabilityToken = 0;

  const authForms = ref<{
    login: LoginForm;
    register: RegisterForm;
    reset: ResetForm;
  }>({
    login: { email: '', password: '' },
    register: { nickname: '', email: '', verificationCode: '', password: '', confirmPassword: '' },
    reset: { email: '', verificationCode: '', password: '', confirmPassword: '' }
  });

  const authErrors = ref<Record<AuthModeKey, Partial<Record<AuthFieldKey, string>>>>({
    login: {},
    register: {},
    reset: {}
  });

  // ---- 打卡 ----
  const checkedInToday = ref(false);
  const checkinStreak = ref(0);
  const checkinCelebrating = ref(false);
  const checkingIn = ref(false);
  const lastCheckinXp = ref(0);

  // ---- 在线心跳 ----
  let presenceHeartbeatTimer: number | undefined;

  function showNotice(message: string, tone: 'neutral' | 'error' = 'neutral') {
    notice.value = message;
    noticeTone.value = tone;
    if (noticeTimer) window.clearTimeout(noticeTimer);
    noticeTimer = window.setTimeout(() => {
      if (notice.value === message) notice.value = '';
    }, 2400);
  }

  function clearNotice() {
    notice.value = '';
    if (noticeTimer) window.clearTimeout(noticeTimer);
  }

  function openDialog(message?: string) {
    if (message) showNotice(message);
    dialogOpen.value = true;
    resetAuthForms();
    clearAuthErrors();
    authSubmitting.value = false;
    verificationCodeSending.value = false;
    authMode.value = 'login';
  }

  function closeDialog() {
    dialogOpen.value = false;
    authSubmitting.value = false;
    verificationCodeSending.value = false;
    clearAuthErrors();
  }

  function resetAuthForms() {
    authForms.value = {
      login: { email: '', password: '' },
      register: { nickname: '', email: '', verificationCode: '', password: '', confirmPassword: '' },
      reset: { email: '', verificationCode: '', password: '', confirmPassword: '' }
    };
  }

  function clearAuthErrors() {
    authErrors.value = { login: {}, register: {}, reset: {} };
  }

  function clearAuthFieldErrors(mode: AuthModeKey, fields: AuthFieldKey[]) {
    for (const field of fields) delete authErrors.value[mode][field];
  }

  function switchMode(mode: AuthModeKey) {
    authMode.value = mode;
    clearAuthErrors();
  }

  function startVerificationCountdown(purpose: 'register' | 'reset') {
    stopVerificationCountdown(purpose);
    const countdown = purpose === 'register' ? registerCodeCountdown : resetCodeCountdown;
    countdown.value = 60;
    const timer = window.setInterval(() => {
      countdown.value = Math.max(0, countdown.value - 1);
      if (countdown.value <= 0) stopVerificationCountdown(purpose);
    }, 1000);
    if (purpose === 'register') registerCodeCountdownTimer = timer;
    else resetCodeCountdownTimer = timer;
  }

  function stopVerificationCountdown(purpose: 'register' | 'reset') {
    if (purpose === 'register') {
      if (registerCodeCountdownTimer) window.clearInterval(registerCodeCountdownTimer);
      registerCodeCountdownTimer = undefined;
      registerCodeCountdown.value = 0;
    } else {
      if (resetCodeCountdownTimer) window.clearInterval(resetCodeCountdownTimer);
      resetCodeCountdownTimer = undefined;
      resetCodeCountdown.value = 0;
    }
  }

  function validateRegisterForm(payload: { email: string; password: string; confirmPassword: string; nickname: string; verificationCode: string }) {
    let hasError = false;
    // 账号必须填；不校验邮箱格式——开发阶段可以用任意字符串当账号。
    if (!payload.email) { authErrors.value.register.email = '请输入账号'; hasError = true; }
    if (!payload.password) { authErrors.value.register.password = '请输入密码'; hasError = true; }
    // 确认密码在界面上仍要求填，但后端允许省略（给 App 端留余地）。
    if (!payload.confirmPassword) { authErrors.value.register.confirmPassword = '请再次输入密码'; hasError = true; }
    else if (payload.password !== payload.confirmPassword) { authErrors.value.register.confirmPassword = '两次密码不一致'; hasError = true; }
    // 验证码必填。开发模式下后端放行任意 4 位数字（app.verification.mock-accept-any-code），
    // 但字段本身照样要填 —— 保证本地和线上是同一条调用链，
    // 不会出现「本地随便点能过、上线才发现漏了校验」。
    if (!payload.verificationCode) { authErrors.value.register.verificationCode = '请输入验证码'; hasError = true; }
    else if (!/^\d{4}$/.test(payload.verificationCode)) { authErrors.value.register.verificationCode = '验证码必须是 4 位数字'; hasError = true; }
    return hasError;
  }

  function validateResetForm(payload: ResetForm) {
    let hasError = false;
    if (!payload.email) { authErrors.value.reset.email = '请输入邮箱'; hasError = true; }
    if (!payload.verificationCode) { authErrors.value.reset.verificationCode = '请输入验证码'; hasError = true; }
    else if (!/^\d{4}$/.test(payload.verificationCode)) { authErrors.value.reset.verificationCode = '验证码必须是 4 位数字'; hasError = true; }
    if (!payload.password) { authErrors.value.reset.password = '请输入新密码'; hasError = true; }
    if (!payload.confirmPassword) { authErrors.value.reset.confirmPassword = '请再次输入新密码'; hasError = true; }
    else if (payload.password !== payload.confirmPassword) { authErrors.value.reset.confirmPassword = '两次密码不一致'; hasError = true; }
    return hasError;
  }

  async function checkRegisterNickname() {
    const nickname = authForms.value.register.nickname.trim();
    clearAuthFieldErrors('register', ['nickname']);
    if (!nickname) return true; // 没填就不校验，后端会用账号兜底
    const token = ++nicknameAvailabilityToken;
    try {
      const result = await authApi.nicknameAvailability(nickname);
      if (token !== nicknameAvailabilityToken) return false;
      if (!result.available) {
        authErrors.value.register.nickname = '该用户名已被占用';
        showNotice('该用户名已被占用，请换一个', 'error');
        return false;
      }
      return true;
    } catch {
      return true;
    }
  }

  async function sendVerificationCode(purpose: 'register' | 'reset') {
    if (verificationCodeSending.value) return;
    if ((purpose === 'register' ? registerCodeCountdown.value : resetCodeCountdown.value) > 0) return;
    clearAuthFieldErrors(purpose, ['email', 'verificationCode']);
    const form = authForms.value[purpose];
    const email = form.email.trim();
    if (!email) {
      authErrors.value[purpose].email = '请输入邮箱';
      return;
    }
    verificationCodeSending.value = true;
    try {
      const apiPurpose = purpose === 'reset' ? 'reset-password' : 'register';
      const availability = await authApi.emailAvailability(email, apiPurpose);
      if (purpose === 'register' && !availability.available) {
        authErrors.value.register.email = '该邮箱已注册';
        showNotice('该邮箱已注册，请直接登录或找回密码', 'error');
        return;
      }
      if (purpose === 'reset' && !availability.available) {
        authErrors.value.reset.email = '该邮箱尚未注册';
        showNotice('该邮箱尚未注册，请先注册账号', 'error');
        return;
      }
      await authApi.requestVerificationCode({ email, purpose: apiPurpose });
      form.verificationCode = '';
      startVerificationCountdown(purpose);
    } catch (error) {
      showNotice(`验证码发送失败：${apiErrorMessage(error, '验证码发送失败')}`, 'error');
    } finally {
      verificationCodeSending.value = false;
    }
  }

  /** 登录/注册成功后的默认收尾：刷新信息流 + 我的数据 + 会话列表 */
  async function defaultOnSuccess() {
    const feed = useFeedStore();
    const profile = useProfileStore();
    const messages = useMessagesStore();
    await Promise.all([
      feed.load(),
      profile.loadMine().catch(() => undefined),
      messages.refreshConversations().catch(() => undefined)
    ]);
  }

  /** 登录/注册成功后的公共收尾；onSuccess 由调用方注入（默认刷新全站数据） */
  async function finishAuth(
    session: { token: string; refreshToken?: string | null },
    user: UserProfile,
    onSuccess?: () => Promise<void> | void
  ) {
    saveAuthToken(session.token, session.refreshToken);
    isAuthenticated.value = true;
    currentUser.value = user;
    startPresenceHeartbeat();
    authSubmitting.value = false;
    closeDialog();
    clearNotice();
    if (onSuccess) await onSuccess();
  }

  async function login(onSuccess?: () => Promise<void> | void) {
    if (authSubmitting.value) return;
    clearAuthErrors();
    const email = authForms.value.login.email.trim();
    const password = authForms.value.login.password;
    let hasError = false;
    if (!email) { authErrors.value.login.email = '请输入邮箱或账号'; hasError = true; }
    if (!password) { authErrors.value.login.password = '请输入密码'; hasError = true; }
    if (hasError) return;
    authSubmitting.value = true;
    let result: { token: string; refreshToken?: string; user: UserProfile };
    try {
      result = await authApi.login(email, password);
    } catch (error) {
      authSubmitting.value = false;
      return showNotice(`登录失败：${error instanceof Error ? error.message : '登录失败'}`, 'error');
    }
    await finishAuth(result, result.user, onSuccess || defaultOnSuccess);
  }

  async function register(onSuccess?: () => Promise<void> | void) {
    if (authSubmitting.value) return;
    clearAuthErrors();
    const payload = {
      email: authForms.value.register.email.trim(),
      nickname: authForms.value.register.nickname.trim(),
      verificationCode: authForms.value.register.verificationCode.trim(),
      password: authForms.value.register.password,
      confirmPassword: authForms.value.register.confirmPassword
    };
    if (validateRegisterForm(payload)) return;
    if (!(await checkRegisterNickname())) return;
    authSubmitting.value = true;
    try {
      const result = await authApi.register(payload);
      await finishAuth(result, result.user, onSuccess || defaultOnSuccess);
    } catch (error) {
      authSubmitting.value = false;
      showNotice(`注册失败：${apiErrorMessage(error, '注册失败')}`, 'error');
    }
  }

  async function resetPassword() {
    if (authSubmitting.value) return;
    clearAuthErrors();
    const payload = {
      email: authForms.value.reset.email.trim(),
      verificationCode: authForms.value.reset.verificationCode.trim(),
      password: authForms.value.reset.password,
      confirmPassword: authForms.value.reset.confirmPassword
    };
    if (validateResetForm(payload)) return;
    authSubmitting.value = true;
    try {
      await authApi.resetPassword(payload);
      authSubmitting.value = false;
      showNotice('密码已重置，请返回登录');
      switchMode('login');
      authForms.value.login.email = payload.email;
      authForms.value.login.password = '';
    } catch (error) {
      authSubmitting.value = false;
      showNotice(`重置密码失败：${apiErrorMessage(error, '重置密码失败')}`, 'error');
    }
  }

  /** 校验本地 token；失效时清理登录态。返回是否有效 */
  async function sessionStillValid() {
    if (!hasAuthToken()) return false;
    try {
      const me = await campusApi.me();
      currentUser.value = me;
      isAuthenticated.value = true;
      return true;
    } catch (error) {
      if (isAuthError(error)) {
        clearAuthToken();
        isAuthenticated.value = false;
        stopPresenceHeartbeat();
      }
      return false;
    }
  }

  /**
   * 统一处理 401：先尝试静默续期，失败则弹登录框。
   * 返回 true 表示已弹出登录框（调用方应中断当前操作）。
   */
  async function handleAuthSensitiveError(error: unknown, loginMessage: string, fallbackMessage?: string) {
    if (isAuthError(error) && !(await sessionStillValid())) {
      openDialog(loginMessage);
      return true;
    }
    showNotice(apiErrorMessage(error, fallbackMessage || loginMessage), 'error');
    return false;
  }

  function applyCheckInView(checkIn: { profile: UserProfile; checkedInToday: boolean; streak: number; xpGained: number }) {
    currentUser.value = checkIn.profile;
    checkedInToday.value = checkIn.checkedInToday;
    checkinStreak.value = checkIn.streak;
    lastCheckinXp.value = checkIn.xpGained;
  }

  function resetCheckInState() {
    checkedInToday.value = false;
    checkinStreak.value = 0;
    checkinCelebrating.value = false;
    lastCheckinXp.value = 0;
  }

  function startPresenceHeartbeat() {
    stopPresenceHeartbeat();
    if (!hasAuthToken()) return;
    sendPresenceHeartbeat();
    presenceHeartbeatTimer = window.setInterval(() => sendPresenceHeartbeat(), 10000);
  }

  function stopPresenceHeartbeat() {
    if (presenceHeartbeatTimer) window.clearInterval(presenceHeartbeatTimer);
    presenceHeartbeatTimer = undefined;
  }

  async function sendPresenceHeartbeat() {
    if (!hasAuthToken()) return;
    try {
      await authApi.heartbeat();
    } catch {
      // 心跳失败静默忽略，下一轮再试
    }
  }

  /** 退出登录：清 token、停心跳、重置所有域数据 */
  async function logout() {
    // 先通知服务端（拉黑 access + refresh），再清本地——顺序反了请求就不带 Authorization 头了。
    const refreshToken = authRefreshToken();
    try {
      await authApi.logout(refreshToken);
    } catch {
      // 网络失败也照样退出本地登录态；服务端那份 token 到过期时间自然失效。
    }
    clearAuthToken();
    isAuthenticated.value = false;
    currentUser.value = guestUser();
    userStats.value = emptyStats();
    stopPresenceHeartbeat();
    useProfileStore().reset();
    useMessagesStore().resetAll();
    useScheduleStore().$reset?.();
    showNotice('已退出登录');
  }

  const hasSession = computed(() => hasAuthToken());

  return {
    currentUser,
    userStats,
    isAuthenticated,
    hasSession,
    notice,
    noticeTone,
    dialogOpen,
    authMode,
    authSubmitting,
    verificationCodeSending,
    registerCodeCountdown,
    resetCodeCountdown,
    authForms,
    authErrors,
    checkedInToday,
    checkinStreak,
    checkinCelebrating,
    checkingIn,
    lastCheckinXp,
    showNotice,
    clearNotice,
    openDialog,
    closeDialog,
    resetAuthForms,
    clearAuthErrors,
    clearAuthFieldErrors,
    switchMode,
    sendVerificationCode,
    logout,
    login,
    register,
    resetPassword,
    sessionStillValid,
    handleAuthSensitiveError,
    applyCheckInView,
    resetCheckInState,
    startPresenceHeartbeat,
    stopPresenceHeartbeat,
    describeError
  };
});