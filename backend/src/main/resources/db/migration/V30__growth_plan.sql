-- A4：成长整合。个人自定义任务（不发经验与金币）+ 活动参与徽章。

-- 学期用起始日期标识（9 月 1 日或 2 月 1 日），没有入学年份的同学也能使用。
CREATE TABLE user_tasks (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT NOT NULL,
    term_start   DATE NOT NULL,
    title        VARCHAR(40) NOT NULL,
    note         VARCHAR(200) NULL,
    due_at       DATETIME NULL,
    completed_at DATETIME NULL,
    created_at   DATETIME NOT NULL,
    updated_at   DATETIME NOT NULL,
    KEY idx_user_tasks_term (user_id, term_start, id),
    CONSTRAINT fk_user_tasks_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 活动徽章只存元数据：是否获得由「签到有效的场次」实时推导（AttendanceBadges），
-- 撤销签到后自然收回，不需要在并发签到/撤销之间维护一份可能过期的 user_badges 行。
INSERT IGNORE INTO badges (code, title, description, rarity, icon, sort_order) VALUES
('activity-1', '第一次到场', '参加 1 场校园活动', 'COMMON', 'figure.walk', 20),
('activity-5', '活动常客', '累计参加 5 场校园活动', 'RARE', 'flag.checkered', 21),
('activity-15', '校园活跃分子', '累计参加 15 场校园活动', 'EPIC', 'star.circle.fill', 22);
