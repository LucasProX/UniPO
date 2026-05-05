CREATE TABLE user_checkins (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  checkin_date DATE NOT NULL,
  streak INT NOT NULL DEFAULT 1,
  xp_gained INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_user_checkins_day (user_id, checkin_date),
  INDEX idx_user_checkins_user_date (user_id, checkin_date),
  CONSTRAINT fk_user_checkins_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO user_checkins (user_id, checkin_date, streak, xp_gained, created_at)
VALUES (1, DATE_SUB(CURRENT_DATE, INTERVAL 1 DAY), 18, 0, NOW());
