CREATE TABLE comment_likes (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  comment_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE (user_id, comment_id)
);
