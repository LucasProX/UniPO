CREATE TABLE schools (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(120) NOT NULL,
  code VARCHAR(80) NOT NULL UNIQUE,
  province VARCHAR(60),
  city VARCHAR(60),
  status VARCHAR(30) NOT NULL DEFAULT 'active',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE users (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  email VARCHAR(160) NOT NULL UNIQUE,
  phone VARCHAR(40),
  password_hash VARCHAR(255) NOT NULL,
  nickname VARCHAR(80) NOT NULL,
  avatar_url VARCHAR(500),
  school_id BIGINT,
  college VARCHAR(120),
  major VARCHAR(120),
  grade VARCHAR(40),
  bio VARCHAR(600),
  verified_status VARCHAR(30) NOT NULL DEFAULT 'none',
  role VARCHAR(30) NOT NULL DEFAULT 'USER',
  status VARCHAR(30) NOT NULL DEFAULT 'active',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  last_login_at DATETIME,
  INDEX idx_users_school_id (school_id),
  CONSTRAINT fk_users_school FOREIGN KEY (school_id) REFERENCES schools(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_preferences (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL UNIQUE,
  goal_tags VARCHAR(500),
  interest_categories VARCHAR(500),
  prefer_urgent BOOLEAN NOT NULL DEFAULT TRUE,
  prefer_high_risk BOOLEAN NOT NULL DEFAULT TRUE,
  prefer_same_major BOOLEAN NOT NULL DEFAULT FALSE,
  email_notification_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  browser_notification_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  remind_before_hours INT NOT NULL DEFAULT 24,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  CONSTRAINT fk_preferences_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE categories (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(80) NOT NULL,
  code VARCHAR(80) NOT NULL UNIQUE,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tags (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(80) NOT NULL,
  type VARCHAR(40) NOT NULL,
  color VARCHAR(20) NOT NULL DEFAULT '#007AFF',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notices (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  title VARCHAR(220) NOT NULL,
  school_id BIGINT NOT NULL,
  college VARCHAR(120),
  grade VARCHAR(60),
  category_id BIGINT NOT NULL,
  importance VARCHAR(30) NOT NULL DEFAULT 'medium',
  risk_level VARCHAR(30) NOT NULL DEFAULT 'medium',
  deadline_at DATETIME,
  official_url VARCHAR(500),
  attachment_url VARCHAR(500),
  summary VARCHAR(500) NOT NULL,
  what_is_it TEXT,
  why_important TEXT,
  suitable_for TEXT,
  not_suitable_for TEXT,
  miss_consequence TEXT,
  next_action TEXT,
  materials_needed TEXT,
  senior_tip TEXT,
  status VARCHAR(30) NOT NULL DEFAULT 'draft',
  is_recommended BOOLEAN NOT NULL DEFAULT FALSE,
  is_pinned BOOLEAN NOT NULL DEFAULT FALSE,
  is_high_risk BOOLEAN NOT NULL DEFAULT FALSE,
  is_urgent BOOLEAN NOT NULL DEFAULT FALSE,
  sort_weight INT NOT NULL DEFAULT 0,
  published_at DATETIME,
  created_by BIGINT,
  updated_by BIGINT,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  FULLTEXT KEY ft_notices_search (title, summary, next_action, miss_consequence),
  INDEX idx_notices_status_deadline (status, deadline_at),
  INDEX idx_notices_category (category_id),
  CONSTRAINT fk_notices_school FOREIGN KEY (school_id) REFERENCES schools(id),
  CONSTRAINT fk_notices_category FOREIGN KEY (category_id) REFERENCES categories(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notice_tags (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  notice_id BIGINT NOT NULL,
  tag_id BIGINT NOT NULL,
  UNIQUE KEY uk_notice_tag (notice_id, tag_id),
  CONSTRAINT fk_notice_tags_notice FOREIGN KEY (notice_id) REFERENCES notices(id) ON DELETE CASCADE,
  CONSTRAINT fk_notice_tags_tag FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE comments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  notice_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  parent_id BIGINT,
  content TEXT NOT NULL,
  like_count INT NOT NULL DEFAULT 0,
  reply_count INT NOT NULL DEFAULT 0,
  is_featured BOOLEAN NOT NULL DEFAULT FALSE,
  status VARCHAR(30) NOT NULL DEFAULT 'visible',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_comments_notice (notice_id, status, created_at),
  CONSTRAINT fk_comments_notice FOREIGN KEY (notice_id) REFERENCES notices(id) ON DELETE CASCADE,
  CONSTRAINT fk_comments_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE favorites (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  notice_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_favorites_user_notice (user_id, notice_id),
  CONSTRAINT fk_favorites_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_favorites_notice FOREIGN KEY (notice_id) REFERENCES notices(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reminders (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  notice_id BIGINT NOT NULL,
  remind_at DATETIME NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'pending',
  channel VARCHAR(30) NOT NULL DEFAULT 'in_app',
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_reminders_user_notice (user_id, notice_id),
  INDEX idx_reminders_due (status, remind_at),
  CONSTRAINT fk_reminders_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_reminders_notice FOREIGN KEY (notice_id) REFERENCES notices(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_notice_status (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  notice_id BIGINT NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'unread',
  completed_at DATETIME,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_status_user_notice (user_id, notice_id),
  CONSTRAINT fk_status_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_status_notice FOREIGN KEY (notice_id) REFERENCES notices(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE analytics_events (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT,
  anonymous_id VARCHAR(120),
  event_name VARCHAR(120) NOT NULL,
  target_type VARCHAR(80),
  target_id BIGINT,
  properties_json JSON,
  created_at DATETIME NOT NULL,
  INDEX idx_analytics_event_time (event_name, created_at),
  CONSTRAINT fk_analytics_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
