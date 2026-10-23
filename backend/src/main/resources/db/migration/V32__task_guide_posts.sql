-- G06：任务关联攻略帖与攻略有效期。
-- 关联只存「哪篇帖子挂在哪个任务下」；能不能看到仍由帖子自己的可见性决定（同校、已发布、未拉黑），
-- 下架或过期的帖子自动不再作为有效攻略，关联行保留，运营可以复核后续期或移除。

ALTER TABLE posts
    ADD COLUMN verified_at DATE NULL COMMENT '运营最近一次核实内容仍然有效的日期',
    ADD COLUMN expires_at DATETIME NULL COMMENT '攻略有效期；过期后不再作为任务的有效攻略';

CREATE TABLE achievement_task_posts (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id    BIGINT NOT NULL,
    post_id    BIGINT NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    added_by   BIGINT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_task_post (task_id, post_id),
    KEY idx_task_posts_task (task_id, sort_order, id),
    CONSTRAINT fk_task_posts_task FOREIGN KEY (task_id) REFERENCES achievement_tasks(id) ON DELETE CASCADE,
    CONSTRAINT fk_task_posts_post FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
