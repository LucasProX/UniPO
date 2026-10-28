-- F08：推荐流加时间衰减。
-- 以前推荐流按 pinned > official > dontMiss > likeCount > commentCount > publishedAt 排，
-- 没有任何衰减，一年前 50 赞的帖子会一直压在今天的帖子上面。
--
-- hot_score = log10(1 + 互动) + 官方加权 + 发布时间（天）
--   互动     = 点赞 + 2 × 评论 + 2 × 收藏
--   官方加权 = 官方 +1，「别错过」再 +0.5
--   发布时间 = 自 2026-01-01 起的天数（审核通过的帖子按通过时间）
-- 晚发一天约等于互动多十倍，第一个赞就有用（+0.3，约 7 小时），官方帖相当于提前一天发布。
-- 分数只在互动或发布时间变化时变，不随「现在」变，所以不用定时刷新，游标翻页也稳定。
-- 生成列由 MySQL 维护，计数怎么加减都不会漏算；改权重要新开迁移改这一列。
ALTER TABLE posts
  ADD COLUMN hot_score DOUBLE AS (
    LOG10(1 + like_count + 2 * comment_count + 2 * favorite_count)
    + IF(official, 1.0, 0) + IF(dont_miss, 0.5, 0)
    + TIMESTAMPDIFF(SECOND, '2026-01-01 00:00:00', COALESCE(published_at, created_at)) / 86400
  ) STORED,
  ADD KEY idx_posts_school_hot (school_id, status, pinned, hot_score, id);
