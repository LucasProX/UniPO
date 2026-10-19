import { defineStore } from 'pinia';
import { ref } from 'vue';
import { campusApi } from '../lib/api';
import { boardLabel, defaultBoards, describeError, enrichApiPosts, normalizeBoards } from '../lib/postHelpers';
import type { BoardCode, BoardView, CampusComment, CampusPost, PostView } from '../types';

/** 信息流（帖子 / 板块 / 评论）的全局状态。Web 与 App 两端共用。 */

export const useFeedStore = defineStore('feed', () => {
  const posts = ref<CampusPost[]>([]);
  const boards = ref<BoardView[]>(defaultBoards());
  const comments = ref<CampusComment[]>([]);
  const feedLoading = ref(false);
  const feedError = ref('');

  const selectedPost = ref<CampusPost | null>(null);

  function boardName(board: BoardCode) {
    return boardLabel(board, boards.value);
  }

  function commentsForPost(post: CampusPost) {
    return comments.value.filter((comment) => comment.postId === post.id);
  }

  async function load() {
    feedLoading.value = true;
    feedError.value = '';
    try {
      const [postList, boardList] = await Promise.all([
        campusApi.posts('recommend'),
        campusApi.boards()
      ]);
      posts.value = enrichApiPosts(postList);
      boards.value = boardList.length ? normalizeBoards(boardList) : defaultBoards();
      comments.value = [];
      selectedPost.value = posts.value[0] || null;
    } catch (error) {
      posts.value = [];
      boards.value = defaultBoards();
      comments.value = [];
      selectedPost.value = null;
      feedError.value = describeError(error, '无法连接后端，请确认服务已启动');
    } finally {
      feedLoading.value = false;
    }
  }

  return {
    posts,
    boards,
    comments,
    feedLoading,
    feedError,
    selectedPost,
    boardName,
    commentsForPost,
    load
  };
});
