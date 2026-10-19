import { defineStore } from 'pinia';
import { ref } from 'vue';
import { apiErrorMessage, authApi, campusApi, mediaApi, userApi } from '../lib/api';
import { enrichApiPosts, fallbackAvatar } from '../lib/postHelpers';
import { useAuthStore } from './auth';
import { useFeedStore } from './feed';
import { useMessagesStore } from './messages';
import type { CampusPost, PostView, PublicProfileView, UserProfile } from '../types';

/** 我的主页 / 他人主页 / 资料编辑 / 头像裁剪。 */

interface ProfileDraft {
  id: number;
  publicUid: string;
  nickname: string;
  avatarUrl: string;
  bio: string;
  motto: string;
  levelTitle: string;
  grade?: string;
}

function stripProfileMotto(bio?: string) {
  return (bio || '').replace(/\n?座右铭：.{0,30}$/u, '').trim();
}

function extractProfileMotto(bio?: string) {
  const match = (bio || '').match(/座右铭：(.{1,30})$/u);
  return match?.[1]?.trim() || '今天也要把有用的事说清楚';
}

function composeProfileBio(bio?: string, motto?: string) {
  const cleanBio = (bio || '').trim().slice(0, 120);
  const cleanMotto = (motto || '').trim().slice(0, 30);
  return cleanMotto ? [cleanBio, `座右铭：${cleanMotto}`].filter(Boolean).join('\n') : cleanBio;
}

function clampNumber(value: number, min: number, max: number) {
  if (min > max) return (min + max) / 2;
  return Math.min(max, Math.max(min, value));
}

function readImageDimensions(url: string) {
  return new Promise<{ width: number; height: number }>((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve({ width: image.naturalWidth, height: image.naturalHeight });
    image.onerror = () => reject(new Error('image load failed'));
    image.src = url;
  });
}

export const useProfileStore = defineStore('profile', () => {
  const auth = useAuthStore();
  const feed = useFeedStore();
  const messages = useMessagesStore();

  const profilePosts = ref<CampusPost[]>([]);
  const profileLikedPosts = ref<CampusPost[]>([]);
  const profileFavoritePosts = ref<CampusPost[]>([]);

  const viewedProfile = ref<PublicProfileView | null>(null);
  const viewedProfilePosts = ref<CampusPost[]>([]);
  const viewedProfileLikedPosts = ref<CampusPost[]>([]);
  const viewedProfileFavoritePosts = ref<CampusPost[]>([]);

  // ---- 资料编辑 ----
  const profileDraft = ref<ProfileDraft | null>(null);
  const profileNicknameChecking = ref(false);
  const profileNicknameError = ref('');
  let profileNicknameAvailabilityToken = 0;

  // ---- 头像上传 / 裁剪 ----
  const profileAvatarPreviewUrl = ref('');
  const profileAvatarUploading = ref(false);
  const profileAvatarUploadError = ref('');
  const avatarCropOpen = ref(false);
  const avatarCropImageUrl = ref('');
  const avatarCropFrame = ref<HTMLElement | null>(null);
  const avatarCropNaturalWidth = ref(0);
  const avatarCropNaturalHeight = ref(0);
  const avatarCropScale = ref(1);
  const avatarCropMinScale = ref(1);
  const avatarCropMaxScale = ref(4);
  const avatarCropOffsetX = ref(0);
  const avatarCropOffsetY = ref(0);

  let avatarCropSourceFile: File | null = null;
  let avatarCropDragState: { pointerId: number; startX: number; startY: number; originX: number; originY: number } | null = null;
  let profileAvatarUploadToken = 0;
  let profileAvatarUploadPromise: Promise<string | null> | null = null;

  function reset() {
    profilePosts.value = [];
    profileLikedPosts.value = [];
    profileFavoritePosts.value = [];
    viewedProfile.value = null;
    viewedProfilePosts.value = [];
    viewedProfileLikedPosts.value = [];
    viewedProfileFavoritePosts.value = [];
    closeEditor();
  }

  // ---- 列表维护 ----

  function setLiked(post: CampusPost, liked: boolean) {
    profileLikedPosts.value = liked
      ? [post, ...profileLikedPosts.value.filter((item) => item.id !== post.id)]
      : profileLikedPosts.value.filter((item) => item.id !== post.id);
    if (viewedProfileLikedPosts.value.some((item) => item.id === post.id)) {
      applyViewedLiked(post, liked);
    }
  }

  function setFavorited(post: CampusPost, favorited: boolean) {
    profileFavoritePosts.value = favorited
      ? [post, ...profileFavoritePosts.value.filter((item) => item.id !== post.id)]
      : profileFavoritePosts.value.filter((item) => item.id !== post.id);
    if (viewedProfileFavoritePosts.value.some((item) => item.id === post.id)) {
      applyViewedFavorited(post, favorited);
    }
  }

  function applyViewedLiked(post: CampusPost, liked: boolean) {
    viewedProfileLikedPosts.value = liked
      ? [post, ...viewedProfileLikedPosts.value.filter((item) => item.id !== post.id)]
      : viewedProfileLikedPosts.value.filter((item) => item.id !== post.id);
  }

  function applyViewedFavorited(post: CampusPost, favorited: boolean) {
    viewedProfileFavoritePosts.value = favorited
      ? [post, ...viewedProfileFavoritePosts.value.filter((item) => item.id !== post.id)]
      : viewedProfileFavoritePosts.value.filter((item) => item.id !== post.id);
  }

  function removePost(postId: number) {
    profilePosts.value = profilePosts.value.filter((item) => item.id !== postId);
    profileLikedPosts.value = profileLikedPosts.value.filter((item) => item.id !== postId);
    profileFavoritePosts.value = profileFavoritePosts.value.filter((item) => item.id !== postId);
    viewedProfilePosts.value = viewedProfilePosts.value.filter((item) => item.id !== postId);
    viewedProfileLikedPosts.value = viewedProfileLikedPosts.value.filter((item) => item.id !== postId);
    viewedProfileFavoritePosts.value = viewedProfileFavoritePosts.value.filter((item) => item.id !== postId);
  }

  /** 用户改了昵称/头像后，把所有该作者的帖子同步 */
  function syncAuthor(userId: number, author: CampusPost['author']) {
    const patch = (list: CampusPost[]) => list.map((post) => post.author.id === userId ? { ...post, author } : post);
    profilePosts.value = patch(profilePosts.value);
    profileLikedPosts.value = patch(profileLikedPosts.value);
    profileFavoritePosts.value = patch(profileFavoritePosts.value);
    viewedProfilePosts.value = patch(viewedProfilePosts.value);
    viewedProfileLikedPosts.value = patch(viewedProfileLikedPosts.value);
    viewedProfileFavoritePosts.value = patch(viewedProfileFavoritePosts.value);
  }

  // ---- 登录后加载 ----

  async function loadMine() {
    const [stats, myPosts, myLikes, myFavorites] = await Promise.all([
      campusApi.stats(),
      campusApi.myPosts(),
      campusApi.myLikes(),
      campusApi.myFavorites()
    ]);
    auth.userStats = stats;
    profilePosts.value = enrichApiPosts(myPosts);
    profileLikedPosts.value = enrichApiPosts(myLikes);
    profileFavoritePosts.value = enrichApiPosts(myFavorites);
  }

  // ---- 他人主页 ----

  async function openAuthorProfile(author: { uid: string; id: number }) {
    if (!auth.hasSession) {
      auth.openDialog('请先登录后查看个人主页');
      return null;
    }
    const uid = author.uid;
    if (!uid || uid === '00000000') {
      auth.showNotice('这个作者没有可访问的主页');
      return null;
    }
    feed.closePost();
    if (auth.isAuthenticated && uid === auth.currentUser.publicUid) return 'mine';
    viewedProfile.value = null;
    try {
      const [profile, authorPosts, likedPosts, favoritePosts] = await Promise.all([
        campusApi.userProfile(uid),
        campusApi.userPosts(uid),
        campusApi.userLikes(uid),
        campusApi.userFavorites(uid)
      ]);
      viewedProfile.value = profile;
      viewedProfilePosts.value = enrichApiPosts(authorPosts);
      viewedProfileLikedPosts.value = enrichApiPosts(likedPosts);
      viewedProfileFavoritePosts.value = enrichApiPosts(favoritePosts);
      return profile.mine ? 'mine' : 'other';
    } catch (error) {
      await auth.handleAuthSensitiveError(error, '登录状态失效，请重新登录后再试', '主页加载失败，请稍后再试');
      return null;
    }
  }

  async function toggleFollow() {
    if (!viewedProfile.value || viewedProfile.value.mine) return;
    if (!auth.hasSession) {
      auth.openDialog('请先登录后关注同学');
      return;
    }
    const uid = viewedProfile.value.profile.publicUid;
    const nextFollowing = !viewedProfile.value.following;
    try {
      if (nextFollowing) await campusApi.follow(uid);
      else await campusApi.unfollow(uid);
    } catch (error) {
      await auth.handleAuthSensitiveError(error, '登录状态失效，请重新登录后再试', nextFollowing ? '关注失败，请稍后再试' : '取消关注失败，请稍后再试');
      return;
    }
    viewedProfile.value = {
      ...viewedProfile.value,
      following: nextFollowing,
      stats: {
        ...viewedProfile.value.stats,
        followers: Math.max(0, viewedProfile.value.stats.followers + (nextFollowing ? 1 : -1))
      }
    };
    auth.userStats.following = Math.max(0, auth.userStats.following + (nextFollowing ? 1 : -1));
    await messages.refreshConversations();
  }

  // ---- 资料编辑 ----

  function openEditor() {
    if (!auth.hasSession) {
      auth.openDialog('请先登录后编辑资料');
      return;
    }
    syncDraft();
  }

  function closeEditor() {
    clearAvatarPreview();
    clearAvatarCrop();
    profileAvatarUploadToken += 1;
    profileAvatarUploading.value = false;
    profileAvatarUploadError.value = '';
    profileNicknameError.value = '';
    profileNicknameChecking.value = false;
    profileAvatarUploadPromise = null;
    profileDraft.value = null;
  }

  function syncDraft() {
    clearAvatarPreview();
    clearAvatarCrop();
    profileAvatarUploadToken += 1;
    profileAvatarUploading.value = false;
    profileAvatarUploadError.value = '';
    profileNicknameError.value = '';
    profileNicknameChecking.value = false;
    profileNicknameAvailabilityToken += 1;
    profileAvatarUploadPromise = null;
    const user = auth.currentUser;
    profileDraft.value = {
      id: user.id,
      publicUid: user.publicUid,
      nickname: user.nickname,
      avatarUrl: user.avatarUrl || fallbackAvatar(user.nickname),
      bio: stripProfileMotto(user.bio),
      motto: extractProfileMotto(user.bio),
      levelTitle: user.levelTitle,
      grade: user.grade
    };
  }

  function clearAvatarPreview() {
    if (profileAvatarPreviewUrl.value) URL.revokeObjectURL(profileAvatarPreviewUrl.value);
    profileAvatarPreviewUrl.value = '';
  }

  async function checkNickname() {
    if (!profileDraft.value) return false;
    const nickname = profileDraft.value.nickname.trim();
    profileNicknameError.value = '';
    if (!nickname) {
      profileNicknameError.value = '名称不能为空';
      return false;
    }
    if (nickname === auth.currentUser.nickname) return true;
    const token = ++profileNicknameAvailabilityToken;
    profileNicknameChecking.value = true;
    try {
      const result = await authApi.nicknameAvailability(nickname);
      if (token !== profileNicknameAvailabilityToken) return false;
      if (!result.available) {
        profileNicknameError.value = '这个名称已经有人用了';
        auth.showNotice('这个名称已经有人用了，请换一个', 'error');
        return false;
      }
      return true;
    } catch (error) {
      auth.showNotice(apiErrorMessage(error, '名称校验失败，请稍后再试'), 'error');
      return false;
    } finally {
      if (token === profileNicknameAvailabilityToken) profileNicknameChecking.value = false;
    }
  }

  async function save() {
    if (!auth.hasSession) {
      closeEditor();
      auth.openDialog('请先登录后编辑资料');
      return;
    }
    if (!profileDraft.value) return;
    if (profileAvatarUploading.value && profileAvatarUploadPromise) {
      const uploadedUrl = await profileAvatarUploadPromise;
      if (!uploadedUrl || !profileDraft.value) return;
    }
    if (!(await checkNickname()) || !profileDraft.value) return;
    let updated: UserProfile | null = null;
    try {
      updated = await userApi.updateProfile({
        nickname: profileDraft.value.nickname.trim(),
        avatarUrl: profileDraft.value.avatarUrl?.trim(),
        bio: composeProfileBio(profileDraft.value.bio, profileDraft.value.motto)
      });
    } catch (error) {
      const message = apiErrorMessage(error, '资料保存失败，请稍后再试');
      if (message.includes('名称') || message.includes('用户名')) profileNicknameError.value = message;
      auth.showNotice(message, 'error');
      return;
    }
    if (updated) {
      auth.currentUser = updated;
      feed.syncAuthorEverywhere(updated.id, feed.authorFromCurrentUser());
    } else {
      auth.currentUser = {
        ...auth.currentUser,
        nickname: profileDraft.value.nickname,
        avatarUrl: profileDraft.value.avatarUrl,
        bio: composeProfileBio(profileDraft.value.bio, profileDraft.value.motto)
      };
    }
    closeEditor();
  }

  // ---- 头像裁剪 ----

  async function handleAvatarFile(file: File) {
    if (!profileDraft.value) return;
    profileAvatarUploadToken += 1;
    profileAvatarUploadError.value = '';
    profileAvatarUploading.value = false;
    profileAvatarUploadPromise = null;
    clearAvatarPreview();
    await openAvatarCrop(file);
  }

  async function openAvatarCrop(file: File) {
    clearAvatarCrop();
    avatarCropSourceFile = file;
    avatarCropImageUrl.value = URL.createObjectURL(file);
    try {
      const dimensions = await readImageDimensions(avatarCropImageUrl.value);
      avatarCropNaturalWidth.value = dimensions.width;
      avatarCropNaturalHeight.value = dimensions.height;
      avatarCropOpen.value = true;
      await Promise.resolve();
      resetAvatarCrop();
    } catch {
      profileAvatarUploadError.value = '图片读取失败，请换一张图片试试';
      clearAvatarCrop();
    }
  }

  function resetAvatarCrop() {
    const frameSize = avatarCropFrame.value?.clientWidth || 300;
    const cropSize = frameSize * 0.72;
    const minScale = Math.max(cropSize / avatarCropNaturalWidth.value, cropSize / avatarCropNaturalHeight.value);
    avatarCropMinScale.value = Number.isFinite(minScale) && minScale > 0 ? minScale : 1;
    avatarCropMaxScale.value = Math.max(4, avatarCropMinScale.value * 3);
    avatarCropScale.value = avatarCropMinScale.value;
    avatarCropOffsetX.value = 0;
    avatarCropOffsetY.value = 0;
    normalizeAvatarCrop();
  }

  function normalizeAvatarCrop() {
    if (!avatarCropNaturalWidth.value || !avatarCropNaturalHeight.value) return;
    if (avatarCropScale.value < avatarCropMinScale.value) avatarCropScale.value = avatarCropMinScale.value;
    if (avatarCropScale.value > avatarCropMaxScale.value) avatarCropScale.value = avatarCropMaxScale.value;
    const frameSize = avatarCropFrame.value?.clientWidth || 300;
    const cropSize = frameSize * 0.72;
    const displayWidth = avatarCropNaturalWidth.value * avatarCropScale.value;
    const displayHeight = avatarCropNaturalHeight.value * avatarCropScale.value;
    avatarCropOffsetX.value = clampNumber(avatarCropOffsetX.value, (cropSize - displayWidth) / 2, (displayWidth - cropSize) / 2);
    avatarCropOffsetY.value = clampNumber(avatarCropOffsetY.value, (cropSize - displayHeight) / 2, (displayHeight - cropSize) / 2);
  }

  function startAvatarCropDrag(event: PointerEvent) {
    if (!avatarCropOpen.value) return;
    avatarCropDragState = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      originX: avatarCropOffsetX.value,
      originY: avatarCropOffsetY.value
    };
    avatarCropFrame.value?.setPointerCapture(event.pointerId);
  }

  function moveAvatarCropDrag(event: PointerEvent) {
    if (!avatarCropDragState || avatarCropDragState.pointerId !== event.pointerId) return;
    avatarCropOffsetX.value = avatarCropDragState.originX + event.clientX - avatarCropDragState.startX;
    avatarCropOffsetY.value = avatarCropDragState.originY + event.clientY - avatarCropDragState.startY;
    normalizeAvatarCrop();
  }

  function endAvatarCropDrag(event: PointerEvent) {
    if (!avatarCropDragState || avatarCropDragState.pointerId !== event.pointerId) return;
    avatarCropFrame.value?.releasePointerCapture(event.pointerId);
    avatarCropDragState = null;
  }

  function clearAvatarCrop() {
    if (avatarCropImageUrl.value) URL.revokeObjectURL(avatarCropImageUrl.value);
    avatarCropOpen.value = false;
    avatarCropImageUrl.value = '';
    avatarCropSourceFile = null;
    avatarCropNaturalWidth.value = 0;
    avatarCropNaturalHeight.value = 0;
    avatarCropScale.value = 1;
    avatarCropMinScale.value = 1;
    avatarCropMaxScale.value = 4;
    avatarCropOffsetX.value = 0;
    avatarCropOffsetY.value = 0;
    avatarCropDragState = null;
  }

  function cancelAvatarCrop() {
    if (profileAvatarUploading.value) return;
    clearAvatarCrop();
  }

  function createCroppedAvatarFile(file: File, crop: { size: number; scale: number; offsetX: number; offsetY: number; naturalWidth: number; naturalHeight: number }) {
    return new Promise<File | null>((resolve) => {
      const image = new Image();
      image.onload = () => {
        const canvas = document.createElement('canvas');
        canvas.width = crop.size;
        canvas.height = crop.size;
        const ctx = canvas.getContext('2d');
        if (!ctx) return resolve(null);
        const sourceSize = crop.size / crop.scale;
        const sourceX = (crop.naturalWidth - sourceSize) / 2 - crop.offsetX / crop.scale;
        const sourceY = (crop.naturalHeight - sourceSize) / 2 - crop.offsetY / crop.scale;
        ctx.drawImage(image, sourceX, sourceY, sourceSize, sourceSize, 0, 0, crop.size, crop.size);
        canvas.toBlob((blob) => {
          if (!blob) return resolve(null);
          resolve(new File([blob], file.name || 'avatar.png', { type: blob.type || 'image/png' }));
        }, 'image/png');
      };
      image.onerror = () => resolve(null);
      image.src = URL.createObjectURL(file);
    });
  }

  async function confirmAvatarCrop() {
    if (!avatarCropSourceFile || !profileDraft.value) return;
    profileAvatarUploadError.value = '';
    profileAvatarUploading.value = true;
    const uploadToken = ++profileAvatarUploadToken;
    const promise = (async () => {
      const frameSize = avatarCropFrame.value?.clientWidth || 300;
      const cropSize = Math.round(frameSize * 0.72);
      const cropped = await createCroppedAvatarFile(avatarCropSourceFile as File, {
        size: cropSize,
        scale: avatarCropScale.value,
        offsetX: avatarCropOffsetX.value,
        offsetY: avatarCropOffsetY.value,
        naturalWidth: avatarCropNaturalWidth.value,
        naturalHeight: avatarCropNaturalHeight.value
      });
      if (!cropped) throw new Error('crop failed');
      const uploaded = await mediaApi.upload(cropped, 'avatar');
      return uploaded.url;
    })();
    profileAvatarUploadPromise = promise;
    try {
      const url = await promise;
      if (uploadToken !== profileAvatarUploadToken || !profileDraft.value) return;
      profileDraft.value.avatarUrl = url;
      clearAvatarCrop();
    } catch (error) {
      if (uploadToken === profileAvatarUploadToken) {
        profileAvatarUploadError.value = apiErrorMessage(error, '头像上传失败，请稍后再试');
      }
    } finally {
      if (uploadToken === profileAvatarUploadToken) {
        profileAvatarUploading.value = false;
        profileAvatarUploadPromise = null;
      }
    }
  }

  return {
    profilePosts,
    profileLikedPosts,
    profileFavoritePosts,
    viewedProfile,
    viewedProfilePosts,
    viewedProfileLikedPosts,
    viewedProfileFavoritePosts,
    profileDraft,
    profileNicknameChecking,
    profileNicknameError,
    profileAvatarPreviewUrl,
    profileAvatarUploading,
    profileAvatarUploadError,
    avatarCropOpen,
    avatarCropImageUrl,
    avatarCropFrame,
    avatarCropScale,
    avatarCropMinScale,
    avatarCropMaxScale,
    avatarCropOffsetX,
    avatarCropOffsetY,
    avatarCropNaturalWidth,
    avatarCropNaturalHeight,
    reset,
    setLiked,
    setFavorited,
    removePost,
    syncAuthor,
    loadMine,
    openAuthorProfile,
    toggleFollow,
    openEditor,
    closeEditor,
    syncDraft,
    clearAvatarPreview,
    checkNickname,
    save,
    handleAvatarFile,
    openAvatarCrop,
    resetAvatarCrop,
    normalizeAvatarCrop,
    startAvatarCropDrag,
    moveAvatarCropDrag,
    endAvatarCropDrag,
    clearAvatarCrop,
    cancelAvatarCrop,
    confirmAvatarCrop
  };
});
