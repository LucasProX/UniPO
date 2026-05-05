CREATE TABLE user_checkins (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  checkin_date DATE NOT NULL,
  streak INT NOT NULL DEFAULT 1,
  xp_gained INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  UNIQUE (user_id, checkin_date)
);

INSERT INTO user_checkins (user_id, checkin_date, streak, xp_gained, created_at)
VALUES (1, DATEADD('DAY', -1, CURRENT_DATE), 18, 0, CURRENT_TIMESTAMP);
