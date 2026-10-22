-- Synthetic R03 drill data on top of V1-V25. No real account, credential or message.
-- Rows deliberately mix emoji, CJK, quotes, backslashes, newlines, JSON, NULLs and DATETIME(6) so the dump
-- round trip is checked on the awkward values, not only on row counts.
INSERT INTO users (public_uid, email, phone, password_hash, nickname, school_id, verified_status, role, status,
                   auth_version, onboarding_completed, created_at, updated_at)
VALUES
  ('R0300001', 'r03-alpha@unipo.invalid', '19900000301', 'r03-not-a-hash', '备份演练甲🐣', 1, 'none', 'USER', 'active', 0, 1, '2026-10-01 08:00:00', '2026-10-01 08:00:00'),
  ('R0300002', 'r03-beta@unipo.invalid', NULL, 'r03-not-a-hash', 'Beta "quoted" \\ user', 1, 'none', 'USER', 'active', 3, 0, '2026-10-02 09:30:00', '2026-10-02 09:30:00'),
  ('R0300003', 'r03-gamma@unipo.invalid', NULL, 'r03-not-a-hash', '恢复演练丙', 1, 'verified', 'USER', 'disabled', 1, 1, '2026-10-03 10:00:00', '2026-10-05 10:00:00'),
  ('R0300004', 'r03-delta@unipo.invalid', NULL, 'r03-not-a-hash', 'delta 注销', 1, 'none', 'USER', 'deleted', 2, 1, '2026-10-04 11:00:00', '2026-10-06 11:00:00');

INSERT INTO posts (author_id, school_id, board, source_type, title, content, excerpt, tags, status, published_at, created_at, updated_at)
SELECT u.id, 1, 'recommend', 'student', '备份演练帖子：多行 + emoji 🎓',
       '第一行\n第二行有 \'单引号\' 和 "双引号"\n反斜杠 \\ 结尾', '摘要', 'r03,备份', 'published',
       '2026-10-10 12:00:00', '2026-10-10 12:00:00', '2026-10-10 12:00:00'
FROM users u WHERE u.email = 'r03-alpha@unipo.invalid';
INSERT INTO posts (author_id, school_id, board, source_type, title, content, status, published_at, created_at, updated_at)
SELECT u.id, 1, 'major', 'student', 'R03 hidden draft', 'hidden body', 'hidden', NULL,
       '2026-10-11 12:00:00', '2026-10-11 12:00:00'
FROM users u WHERE u.email = 'r03-beta@unipo.invalid';

INSERT INTO comments (post_id, user_id, parent_id, content, status, request_key, created_at, updated_at)
SELECT p.id, u.id, NULL, '评论里也有 emoji 🙂 和 tab\there', 'visible', 'r03-comment-key-1',
       '2026-10-10 13:00:00', '2026-10-10 13:00:00'
FROM posts p JOIN users u ON u.email = 'r03-beta@unipo.invalid'
WHERE p.title = '备份演练帖子：多行 + emoji 🎓';

INSERT INTO achievement_progress (user_id, task_id, completed_at)
SELECT u.id, t.id, '2026-10-12 08:00:00'
FROM users u JOIN achievement_tasks t ON t.title IN ('完成报到', '激活校园卡', '四级报名')
WHERE u.email = 'r03-alpha@unipo.invalid';
INSERT INTO achievement_progress (user_id, task_id, completed_at)
SELECT u.id, t.id, '2026-10-12 09:00:00'
FROM users u JOIN achievement_tasks t ON t.title = '完成报到'
WHERE u.email = 'r03-beta@unipo.invalid';

INSERT INTO user_follows (follower_id, following_id, created_at)
SELECT a.id, b.id, '2026-10-13 08:00:00'
FROM users a JOIN users b ON b.email = 'r03-beta@unipo.invalid'
WHERE a.email = 'r03-alpha@unipo.invalid';

-- One conversation with messages and one empty one, so the generated activity_at covers both COALESCE branches.
INSERT INTO conversations (user_one_id, user_two_id, last_message_at, created_at, updated_at)
SELECT LEAST(a.id, b.id), GREATEST(a.id, b.id), '2026-10-14 10:02:00', '2026-10-14 10:00:00', '2026-10-14 10:02:00'
FROM users a JOIN users b ON b.email = 'r03-beta@unipo.invalid'
WHERE a.email = 'r03-alpha@unipo.invalid';
INSERT INTO conversations (user_one_id, user_two_id, last_message_at, created_at, updated_at)
SELECT LEAST(a.id, b.id), GREATEST(a.id, b.id), NULL, '2026-10-15 10:00:00', '2026-10-15 10:00:00'
FROM users a JOIN users b ON b.email = 'r03-gamma@unipo.invalid'
WHERE a.email = 'r03-alpha@unipo.invalid';

INSERT INTO messages (conversation_id, sender_id, receiver_id, content, is_read, client_message_id, created_at)
SELECT c.id, a.id, b.id, '你好 👋\n第二行', TRUE, 'r03-intent-0001', '2026-10-14 10:00:00'
FROM users a JOIN users b ON b.email = 'r03-beta@unipo.invalid'
JOIN conversations c ON c.user_one_id = LEAST(a.id, b.id) AND c.user_two_id = GREATEST(a.id, b.id)
WHERE a.email = 'r03-alpha@unipo.invalid';
INSERT INTO messages (conversation_id, sender_id, receiver_id, content, is_read, client_message_id, created_at)
SELECT c.id, b.id, a.id, 'reply with \\backslash and \'quote\'', FALSE, NULL, '2026-10-14 10:01:00'
FROM users a JOIN users b ON b.email = 'r03-beta@unipo.invalid'
JOIN conversations c ON c.user_one_id = LEAST(a.id, b.id) AND c.user_two_id = GREATEST(a.id, b.id)
WHERE a.email = 'r03-alpha@unipo.invalid';
INSERT INTO messages (conversation_id, sender_id, receiver_id, content, is_read, client_message_id, created_at)
SELECT c.id, a.id, b.id, '最后一条 🧪', FALSE, 'r03-intent-0003', '2026-10-14 10:02:00'
FROM users a JOIN users b ON b.email = 'r03-beta@unipo.invalid'
JOIN conversations c ON c.user_one_id = LEAST(a.id, b.id) AND c.user_two_id = GREATEST(a.id, b.id)
WHERE a.email = 'r03-alpha@unipo.invalid';

INSERT INTO message_delivery_outbox (event_id, message_id, channel, state, attempts, available_at, created_at, completed_at)
SELECT 'r0300000000000000000000000000001', m.id, 'PUSH', 'DELIVERED', 1,
       '2026-10-14 02:02:00.123456', '2026-10-14 02:02:00.123456', '2026-10-14 02:02:01.654321'
FROM messages m WHERE m.client_message_id = 'r03-intent-0003';

INSERT INTO analytics_events (user_id, anonymous_id, event_name, target_type, target_id, properties_json, created_at)
SELECT u.id, NULL, 'r03_drill', 'post', NULL, JSON_OBJECT('note', '备份 ✅', 'nested', JSON_ARRAY(1, 2.5, NULL)),
       '2026-10-16 08:00:00'
FROM users u WHERE u.email = 'r03-alpha@unipo.invalid';
