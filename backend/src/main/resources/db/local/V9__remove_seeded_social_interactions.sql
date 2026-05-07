DELETE FROM user_follows
WHERE (follower_id, following_id) IN (
    SELECT follower.id, following.id
    FROM users follower
    JOIN users following ON 1 = 1
    WHERE (follower.email = 'good@biecuoguo.local' AND following.email = 'xiaopo@example.com')
       OR (follower.email = 'xiaopo@example.com' AND following.email = 'school-op@example.com')
       OR (follower.email = 'xiaopo@example.com' AND following.email = 'college-op@example.com')
);

DELETE FROM messages
WHERE conversation_id IN (
    SELECT c.id
    FROM conversations c
    JOIN users u1 ON u1.id = c.user_one_id
    JOIN users u2 ON u2.id = c.user_two_id
    WHERE u1.email IN ('good@biecuoguo.local', 'xiaopo@example.com', 'school-op@example.com', 'college-op@example.com')
      AND u2.email IN ('good@biecuoguo.local', 'xiaopo@example.com', 'school-op@example.com', 'college-op@example.com')
);

DELETE FROM conversations
WHERE id IN (
    SELECT c.id
    FROM conversations c
    JOIN users u1 ON u1.id = c.user_one_id
    JOIN users u2 ON u2.id = c.user_two_id
    LEFT JOIN messages m ON m.conversation_id = c.id
    WHERE u1.email IN ('good@biecuoguo.local', 'xiaopo@example.com', 'school-op@example.com', 'college-op@example.com')
      AND u2.email IN ('good@biecuoguo.local', 'xiaopo@example.com', 'school-op@example.com', 'college-op@example.com')
      AND m.id IS NULL
);
