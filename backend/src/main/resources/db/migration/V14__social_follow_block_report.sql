-- 关注/拉黑/举报（2026-10-20）
--
-- 关注列表、移除粉丝、拉黑、举报用户。
-- 新增粉丝的“+n”用 users.followers_seen_at 做服务端游标，跨设备一致；
-- 第一次没有该值时视为“刚刚看过”，避免老用户一进主页看到 +全部粉丝。

ALTER TABLE users ADD COLUMN followers_seen_at DATETIME NULL;

CREATE TABLE IF NOT EXISTS user_blocks (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    blocker_id BIGINT   NOT NULL,
    blocked_id BIGINT   NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_user_block (blocker_id, blocked_id),
    KEY idx_user_block_blocked (blocked_id),
    CONSTRAINT fk_user_blocks_blocker FOREIGN KEY (blocker_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_blocks_blocked FOREIGN KEY (blocked_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_reports (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    reporter_id    BIGINT       NOT NULL,
    target_user_id BIGINT       NOT NULL,
    category       VARCHAR(30)  NOT NULL,
    detail         VARCHAR(500) NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'pending',
    created_at     DATETIME     NOT NULL,
    KEY idx_user_reports_status (status, created_at),
    KEY idx_user_reports_target (target_user_id),
    CONSTRAINT fk_user_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_reports_target FOREIGN KEY (target_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
