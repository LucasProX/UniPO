-- 成就系统（2026-10-20）
-- 第一阶段只做「通用主线」：章节 -> 任务 -> 用户进度 -> 徽章。
-- 专业支线、活动徽章、算法推荐后续再加表，不改公开接口。

CREATE TABLE IF NOT EXISTS achievement_stages (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(40) NOT NULL,
    title       VARCHAR(40) NOT NULL,
    subtitle    VARCHAR(120) NULL,
    stage_order INT NOT NULL,
    boss        TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_achievement_stage_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS achievement_tasks (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    stage_id    BIGINT NOT NULL,
    title       VARCHAR(80) NOT NULL,
    description VARCHAR(300) NULL,
    task_order  INT NOT NULL DEFAULT 0,
    xp          INT NOT NULL DEFAULT 0,
    badge_code  VARCHAR(40) NULL,
    KEY idx_achievement_tasks_stage (stage_id),
    CONSTRAINT fk_achievement_tasks_stage FOREIGN KEY (stage_id) REFERENCES achievement_stages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS achievement_progress (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT NOT NULL,
    task_id      BIGINT NOT NULL,
    completed_at DATETIME NOT NULL,
    UNIQUE KEY uk_achievement_progress (user_id, task_id),
    KEY idx_achievement_progress_user (user_id),
    CONSTRAINT fk_achievement_progress_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_achievement_progress_task FOREIGN KEY (task_id) REFERENCES achievement_tasks(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS badges (
    code        VARCHAR(40) PRIMARY KEY,
    title       VARCHAR(40) NOT NULL,
    description VARCHAR(200) NULL,
    rarity      VARCHAR(20) NOT NULL,
    icon        VARCHAR(40) NOT NULL,
    sort_order  INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_badges (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    badge_code VARCHAR(40) NOT NULL,
    earned_at  DATETIME NOT NULL,
    UNIQUE KEY uk_user_badge (user_id, badge_code),
    KEY idx_user_badges_user (user_id),
    CONSTRAINT fk_user_badges_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_badges_badge FOREIGN KEY (badge_code) REFERENCES badges(code) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO achievement_stages (code, title, subtitle, stage_order, boss) VALUES
('freshman-1', '大一上', '适应与探索', 1, 0),
('freshman-2', '大一下', '四级 · 社团 · 转专业', 2, 0),
('sophomore', '大二', '竞赛 · 实验室', 3, 0),
('junior', '大三', '实习 · 交换', 4, 0),
('senior', '大四', '求职 / 考研 / 考公', 5, 1);

INSERT IGNORE INTO achievement_tasks (stage_id, title, description, task_order, xp, badge_code)
SELECT id, '完成报到', '带齐材料，找到自己的学院报到处。', 1, 20, 'freshman-start' FROM achievement_stages WHERE code='freshman-1';
INSERT IGNORE INTO achievement_tasks (stage_id, title, description, task_order, xp, badge_code)
SELECT id, '激活校园卡', '食堂、图书馆、门禁都靠它。', 2, 15, NULL FROM achievement_stages WHERE code='freshman-1';
INSERT IGNORE INTO achievement_tasks (stage_id, title, description, task_order, xp, badge_code)
SELECT id, '确认本学期选课', '退改选截止前核对课表。', 3, 20, NULL FROM achievement_stages WHERE code='freshman-1';
INSERT IGNORE INTO achievement_tasks (stage_id, title, description, task_order, xp, badge_code)
SELECT id, '四级报名', '错过报名只能等下一次。', 4, 30, 'cet4-signup' FROM achievement_stages WHERE code='freshman-1';

INSERT IGNORE INTO achievement_tasks (stage_id, title, description, task_order, xp, badge_code)
SELECT id, '参加一次社团面试', '至少去一个感兴趣的组织看看。', 1, 25, 'club-interview' FROM achievement_stages WHERE code='freshman-2';
INSERT IGNORE INTO achievement_tasks (stage_id, title, description, task_order, xp, badge_code)
SELECT id, '写一份期末复习计划', '把课程按难度排一遍。', 2, 20, NULL FROM achievement_stages WHERE code='freshman-2';
INSERT IGNORE INTO achievement_tasks (stage_id, title, description, task_order, xp, badge_code)
SELECT id, '了解转专业政策', '看清门槛和时间窗。', 3, 20, NULL FROM achievement_stages WHERE code='freshman-2';

INSERT IGNORE INTO achievement_tasks (stage_id, title, description, task_order, xp, badge_code)
SELECT id, '参加一次竞赛或实验室', '先报名，再谈结果。', 1, 30, NULL FROM achievement_stages WHERE code='sophomore';
INSERT IGNORE INTO achievement_tasks (stage_id, title, description, task_order, xp, badge_code)
SELECT id, '整理一份简历初稿', '把课程、项目、社团写进去。', 2, 30, NULL FROM achievement_stages WHERE code='sophomore';

INSERT IGNORE INTO achievement_tasks (stage_id, title, description, task_order, xp, badge_code)
SELECT id, '完成一次实习投递', '投出去比完美更重要。', 1, 40, NULL FROM achievement_stages WHERE code='junior';
INSERT IGNORE INTO achievement_tasks (stage_id, title, description, task_order, xp, badge_code)
SELECT id, '确定毕业方向', '求职 / 考研 / 考公，选一个主攻。', 2, 30, NULL FROM achievement_stages WHERE code='junior';

INSERT IGNORE INTO achievement_tasks (stage_id, title, description, task_order, xp, badge_code)
SELECT id, '拿到一个 Offer 或录取', '最终 Boss 通关。', 1, 100, 'final-boss' FROM achievement_stages WHERE code='senior';

INSERT IGNORE INTO badges (code, title, description, rarity, icon, sort_order) VALUES
('freshman-start', '初来乍到', '完成报到', 'COMMON', 'sparkles', 1),
('cet4-signup', '四级报名', '完成四级报名', 'RARE', 'checkmark.seal.fill', 2),
('club-interview', '社团面试', '参加一次社团面试', 'RARE', 'person.3.fill', 3),
('library-regular', '图书馆常客', '连续自习 7 天（运营发放）', 'EPIC', 'books.vertical.fill', 4),
('volunteer-2026', '2026 迎新志愿者', '参与 2026 迎新（限定）', 'LIMITED', 'heart.fill', 5),
('final-boss', '毕业通关', '拿到 Offer 或录取', 'LEGENDARY', 'crown.fill', 6);
