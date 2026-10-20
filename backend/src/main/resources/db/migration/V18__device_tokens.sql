-- 推送设备与账号注销（2026-10-20）
-- device_tokens 支持同一用户多设备；同一 token 只保留一条（换账号时覆盖）。

CREATE TABLE IF NOT EXISTS device_tokens (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    token        VARCHAR(255) NOT NULL,
    platform     VARCHAR(20)  NOT NULL DEFAULT 'ios',
    environment  VARCHAR(20)  NOT NULL DEFAULT 'sandbox',
    created_at   DATETIME     NOT NULL,
    last_seen_at DATETIME     NOT NULL,
    UNIQUE KEY uk_device_token (token),
    KEY idx_device_tokens_user (user_id),
    CONSTRAINT fk_device_tokens_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
