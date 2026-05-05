ALTER TABLE users ADD COLUMN public_uid VARCHAR(8);
ALTER TABLE users ADD COLUMN college_id BIGINT;
ALTER TABLE users ADD COLUMN major_id BIGINT;
ALTER TABLE users ADD COLUMN operator_scope VARCHAR(30) NOT NULL DEFAULT 'student';
ALTER TABLE users ADD COLUMN level INT NOT NULL DEFAULT 1;
ALTER TABLE users ADD COLUMN xp INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN level_title VARCHAR(80) NOT NULL DEFAULT '萌新探路员';
UPDATE users SET public_uid = LPAD(CAST(10000000 + id AS VARCHAR), 8, '0') WHERE public_uid IS NULL;
ALTER TABLE users ALTER COLUMN public_uid SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT uk_users_public_uid UNIQUE (public_uid);

CREATE TABLE colleges (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  school_id BIGINT NOT NULL,
  name VARCHAR(120) NOT NULL,
  code VARCHAR(80) NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'active',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE (school_id, code)
);

CREATE TABLE majors (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  school_id BIGINT NOT NULL,
  college_id BIGINT NOT NULL,
  name VARCHAR(120) NOT NULL,
  code VARCHAR(80) NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'active',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE (college_id, code)
);

CREATE TABLE posts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  author_id BIGINT NOT NULL,
  school_id BIGINT NOT NULL,
  college_id BIGINT,
  major_id BIGINT,
  board VARCHAR(30) NOT NULL,
  source_type VARCHAR(30) NOT NULL DEFAULT 'student',
  title VARCHAR(160) NOT NULL,
  content TEXT NOT NULL,
  excerpt VARCHAR(240),
  cover_url VARCHAR(500),
  risk_level VARCHAR(30) NOT NULL DEFAULT 'normal',
  official BOOLEAN NOT NULL DEFAULT FALSE,
  dont_miss BOOLEAN NOT NULL DEFAULT FALSE,
  pinned BOOLEAN NOT NULL DEFAULT FALSE,
  deadline_at DATETIME,
  miss_consequence VARCHAR(500),
  next_action VARCHAR(500),
  tags VARCHAR(500),
  like_count INT NOT NULL DEFAULT 0,
  comment_count INT NOT NULL DEFAULT 0,
  favorite_count INT NOT NULL DEFAULT 0,
  share_count INT NOT NULL DEFAULT 0,
  status VARCHAR(30) NOT NULL DEFAULT 'published',
  published_at DATETIME,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL
);

CREATE TABLE post_images (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  url VARCHAR(500) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL
);

CREATE TABLE post_likes (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  post_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE (user_id, post_id)
);

CREATE TABLE post_favorites (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  post_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE (user_id, post_id)
);

CREATE TABLE post_shares (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  post_id BIGINT NOT NULL,
  channel VARCHAR(40) NOT NULL DEFAULT 'copy_link',
  created_at DATETIME NOT NULL
);

CREATE TABLE post_daily_stats (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  post_id BIGINT NOT NULL,
  stat_date DATE NOT NULL,
  like_count INT NOT NULL DEFAULT 0,
  comment_count INT NOT NULL DEFAULT 0,
  share_count INT NOT NULL DEFAULT 0,
  UNIQUE (post_id, stat_date)
);

CREATE TABLE user_follows (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  follower_id BIGINT NOT NULL,
  following_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE (follower_id, following_id)
);

CREATE TABLE conversations (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_one_id BIGINT NOT NULL,
  user_two_id BIGINT NOT NULL,
  last_message_at DATETIME,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE (user_one_id, user_two_id)
);

CREATE TABLE messages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  conversation_id BIGINT NOT NULL,
  sender_id BIGINT NOT NULL,
  receiver_id BIGINT NOT NULL,
  content TEXT NOT NULL,
  is_read BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME NOT NULL
);

CREATE TABLE media_objects (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  bucket VARCHAR(120) NOT NULL,
  object_key VARCHAR(500) NOT NULL,
  url VARCHAR(700) NOT NULL,
  content_type VARCHAR(120),
  size_bytes BIGINT,
  purpose VARCHAR(40),
  created_at DATETIME NOT NULL
);

ALTER TABLE comments ALTER COLUMN notice_id DROP NOT NULL;
ALTER TABLE comments ADD COLUMN post_id BIGINT NULL;
CREATE INDEX idx_comments_post ON comments(post_id, status, created_at);
