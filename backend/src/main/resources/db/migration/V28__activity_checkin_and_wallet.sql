-- A2：活动签到与金币账本。
ALTER TABLE activities
    ADD COLUMN checkin_secret CHAR(64) NULL COMMENT '动态二维码签名密钥，只在服务端使用',
    ADD COLUMN checkin_code CHAR(4) NULL COMMENT '现场公布的 4 位签到码，只在签到时间窗内有效';

UPDATE activities
SET checkin_secret = SHA2(CONCAT(id, ':', UUID(), ':', RAND()), 256),
    checkin_code = LPAD(FLOOR(RAND() * 10000), 4, '0')
WHERE checkin_secret IS NULL;

ALTER TABLE activity_registrations
    ADD COLUMN checked_in_at DATETIME NULL,
    ADD COLUMN checkin_method VARCHAR(10) NULL COMMENT 'QR/CODE/MANUAL',
    ADD COLUMN reward_xp_granted INT NOT NULL DEFAULT 0,
    ADD COLUMN reward_coins_granted INT NOT NULL DEFAULT 0,
    ADD KEY idx_activity_registrations_checkin (activity_id, checked_in_at);

-- 金币余额只由流水推进：每笔有唯一幂等键，余额行在同一事务里加锁更新。
CREATE TABLE user_wallets (
    user_id BIGINT PRIMARY KEY,
    balance INT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL,
    CONSTRAINT fk_user_wallets_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE coin_ledger (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    amount INT NOT NULL COMMENT '正数入账，负数扣回',
    balance_after INT NOT NULL,
    source_type VARCHAR(32) NOT NULL COMMENT 'ACTIVITY_CHECKIN 等',
    source_id BIGINT NULL,
    idempotency_key VARCHAR(80) NOT NULL,
    reason VARCHAR(120) NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_coin_ledger_key (idempotency_key),
    KEY idx_coin_ledger_user (user_id, id),
    CONSTRAINT fk_coin_ledger_user FOREIGN KEY (user_id) REFERENCES users(id)
);
