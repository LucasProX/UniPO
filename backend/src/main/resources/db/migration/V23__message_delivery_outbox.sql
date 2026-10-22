-- Message facts and these delivery intents are committed in the same transaction.
-- All operational timestamps are written explicitly using UTC_TIMESTAMP(6).
CREATE TABLE message_delivery_outbox (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id      CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    message_id    BIGINT NOT NULL,
    channel       VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state         VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    attempts      INT NOT NULL DEFAULT 0,
    available_at  DATETIME(6) NOT NULL,
    lease_until   DATETIME(6) NULL,
    claim_token   CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_error    VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at    DATETIME(6) NOT NULL,
    completed_at  DATETIME(6) NULL,
    UNIQUE KEY uk_message_outbox_event (event_id),
    UNIQUE KEY uk_message_outbox_message_channel (message_id, channel),
    KEY idx_message_outbox_pending (state, available_at, id),
    KEY idx_message_outbox_lease (state, lease_until, id),
    CONSTRAINT fk_message_outbox_message FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
    CONSTRAINT ck_message_outbox_channel CHECK (channel IN ('REALTIME', 'PUSH')),
    CONSTRAINT ck_message_outbox_state CHECK (state IN ('PENDING', 'SENDING', 'DELIVERED', 'SUPPRESSED', 'FAILED')),
    CONSTRAINT ck_message_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_message_outbox_event CHECK (CHAR_LENGTH(event_id) = 32),
    CONSTRAINT ck_message_outbox_claim CHECK (
        (state = 'SENDING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (state <> 'SENDING' AND claim_token IS NULL AND lease_until IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
