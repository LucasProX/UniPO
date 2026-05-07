DELETE uf
FROM user_follows uf
JOIN users follower ON follower.id = uf.follower_id
JOIN users following ON following.id = uf.following_id
WHERE (follower.email = 'good@biecuoguo.local' AND following.email = 'xiaopo@example.com')
   OR (follower.email = 'xiaopo@example.com' AND following.email = 'school-op@example.com')
   OR (follower.email = 'xiaopo@example.com' AND following.email = 'college-op@example.com');

DELETE m
FROM messages m
JOIN conversations c ON c.id = m.conversation_id
JOIN users u1 ON u1.id = c.user_one_id
JOIN users u2 ON u2.id = c.user_two_id
WHERE u1.email IN ('good@biecuoguo.local', 'xiaopo@example.com', 'school-op@example.com', 'college-op@example.com')
  AND u2.email IN ('good@biecuoguo.local', 'xiaopo@example.com', 'school-op@example.com', 'college-op@example.com');

DELETE c
FROM conversations c
JOIN users u1 ON u1.id = c.user_one_id
JOIN users u2 ON u2.id = c.user_two_id
LEFT JOIN messages m ON m.conversation_id = c.id
WHERE u1.email IN ('good@biecuoguo.local', 'xiaopo@example.com', 'school-op@example.com', 'college-op@example.com')
  AND u2.email IN ('good@biecuoguo.local', 'xiaopo@example.com', 'school-op@example.com', 'college-op@example.com')
  AND m.id IS NULL;
