-- Serialize one user pair without exclusively locking either user row.
-- Callers first take shared participant status locks, then acquire this pair lock.
CREATE TABLE social_relationship_pair_locks (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_low_id  BIGINT NOT NULL,
    user_high_id BIGINT NOT NULL,
    UNIQUE KEY uk_social_relationship_pair (user_low_id, user_high_id),
    CONSTRAINT ck_social_relationship_pair_order CHECK (user_low_id < user_high_id),
    CONSTRAINT fk_social_relationship_pair_low FOREIGN KEY (user_low_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_social_relationship_pair_high FOREIGN KEY (user_high_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
