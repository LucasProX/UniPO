ALTER TABLE users ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE auth_device_sessions (
  session_id VARCHAR(43) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  user_id BIGINT NOT NULL,
  auth_version BIGINT NOT NULL,
  expires_at_ms BIGINT NOT NULL,
  created_at_ms BIGINT NOT NULL,
  revoked_at_ms BIGINT NULL,
  INDEX idx_auth_device_sessions_user_version (user_id, auth_version, revoked_at_ms),
  INDEX idx_auth_device_sessions_expiry (expires_at_ms),
  CONSTRAINT fk_auth_device_sessions_user FOREIGN KEY (user_id) REFERENCES users(id),
  CONSTRAINT chk_auth_device_sessions_id_length CHECK (CHAR_LENGTH(session_id) = 43),
  CONSTRAINT chk_auth_device_sessions_lifetime CHECK (expires_at_ms > created_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
