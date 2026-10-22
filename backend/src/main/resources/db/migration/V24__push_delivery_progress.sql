-- Provider progress contains identifiers/outcomes only; no notification body or device credential copies.
CREATE TABLE push_delivery_batches (
    event_id              CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    user_id               BIGINT NOT NULL,
    snapshot_max_token_id  BIGINT NOT NULL,
    next_after_token_id    BIGINT NOT NULL DEFAULT 0,
    created_at            DATETIME(6) NOT NULL,
    CONSTRAINT fk_push_delivery_batch_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE push_delivery_receipts (
    event_id         CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    device_token_id  BIGINT NOT NULL,
    outcome          VARCHAR(16) NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id, device_token_id),
    CONSTRAINT fk_push_delivery_receipt_batch FOREIGN KEY (event_id)
        REFERENCES push_delivery_batches(event_id) ON DELETE CASCADE,
    CONSTRAINT ck_push_delivery_receipt_outcome CHECK (outcome IN ('ACCEPTED', 'INVALID'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
