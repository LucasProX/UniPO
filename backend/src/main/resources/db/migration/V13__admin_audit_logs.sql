-- 管理后台审计日志（2026-10-20）
--
-- 背景：后台是「少数人做高影响操作」的地方（封号、改角色、下架帖子、改运营位）。
-- 没有审计，出问题连谁改的都查不到。第一版只记「谁、对什么、做了什么、结果摘要」，
-- 不做完整 before/after diff；等后台页面稳定后再决定要不要结构化快照。

CREATE TABLE IF NOT EXISTS admin_audit_logs (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_id    BIGINT       NULL,
    actor_email VARCHAR(120) NULL,
    action      VARCHAR(80)  NOT NULL,
    target_type VARCHAR(40)  NULL,
    target_id   VARCHAR(64)  NULL,
    detail      VARCHAR(1000) NULL,
    request_id  VARCHAR(64)  NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_audit_created (created_at),
    KEY idx_audit_actor (actor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
