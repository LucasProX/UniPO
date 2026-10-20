-- 手机号登录 + 短信验证码（2026-10-20）
--
-- 背景：iOS 端只做手机号登录。真实短信通道（阿里云/腾讯云）还没接，
-- 本地用 mock 固定码 123456；非 local 环境调用发送会直接报“短信服务未配置”，
-- 不会静默放行 —— 上线前必须接真实 provider。

ALTER TABLE users ADD UNIQUE KEY uk_users_phone (phone);

CREATE TABLE IF NOT EXISTS sms_codes (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    phone      VARCHAR(40)  NOT NULL,
    purpose    VARCHAR(30)  NOT NULL DEFAULT 'login',
    code_hash  VARCHAR(100) NOT NULL,
    expires_at DATETIME     NOT NULL,
    consumed   TINYINT(1)   NOT NULL DEFAULT 0,
    created_at DATETIME     NOT NULL,
    KEY idx_sms_codes_phone (phone, purpose, consumed),
    KEY idx_sms_codes_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
