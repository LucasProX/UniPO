-- App 内「意见反馈」。正文可能带个人信息，只给管理员看，不进日志。
CREATE TABLE user_feedback (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    category    VARCHAR(20)  NOT NULL,
    content     VARCHAR(1000) NOT NULL,
    app_version VARCHAR(40)  NULL,
    device      VARCHAR(60)  NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'new',
    created_at  DATETIME     NOT NULL,
    KEY idx_feedback_created (created_at, id),
    KEY idx_feedback_user (user_id, created_at),
    CONSTRAINT fk_feedback_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
