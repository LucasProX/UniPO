-- F13：作者编辑帖子后记录编辑时间，详情页显示「已编辑」。编辑不改发布时间，所以不影响推荐分。
ALTER TABLE posts ADD COLUMN edited_at DATETIME NULL AFTER published_at;

-- F15：主页称号。每人最多选一枚已获得的徽章；徽章失效（例如活动签到被撤销）后不再展示，行保留到下次更换。
CREATE TABLE achievement_titles (
    user_id    BIGINT PRIMARY KEY,
    badge_code VARCHAR(40) NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT fk_achievement_titles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_achievement_titles_badge FOREIGN KEY (badge_code) REFERENCES badges(code) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
