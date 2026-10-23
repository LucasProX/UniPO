-- A3：报名审核、撤销签到、活动变更取消与站内通知。
ALTER TABLE activities
    ADD COLUMN require_approval TINYINT(1) NOT NULL DEFAULT 0 COMMENT '报名需组织者通过',
    ADD COLUMN cancel_reason VARCHAR(200) NULL,
    ADD COLUMN cancelled_at DATETIME NULL;

ALTER TABLE activity_registrations
    ADD COLUMN reviewed_by BIGINT NULL,
    ADD COLUMN reviewed_at DATETIME NULL,
    ADD COLUMN checkin_revoked_at DATETIME NULL COMMENT '组织者撤销签到后不能再次签到',
    ADD COLUMN revoke_reason VARCHAR(200) NULL;

-- 站内通知：活动变更、取消、审核结果、候补转正、开始提醒、签到撤销。
-- dedupe_key 保证同一事件对同一用户只写一次（定时提醒可重复扫描）。
CREATE TABLE user_notifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    category VARCHAR(16) NOT NULL COMMENT 'ACTIVITY',
    type VARCHAR(32) NOT NULL,
    title VARCHAR(80) NOT NULL,
    body VARCHAR(300) NOT NULL,
    activity_id BIGINT NULL,
    dedupe_key VARCHAR(120) NOT NULL,
    created_at DATETIME NOT NULL,
    read_at DATETIME NULL,
    UNIQUE KEY uk_user_notifications_dedupe (user_id, dedupe_key),
    KEY idx_user_notifications_user (user_id, id),
    KEY idx_user_notifications_unread (user_id, read_at),
    CONSTRAINT fk_user_notifications_user FOREIGN KEY (user_id) REFERENCES users(id)
);
