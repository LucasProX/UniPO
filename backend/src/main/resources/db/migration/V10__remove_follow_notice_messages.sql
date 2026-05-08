DELETE FROM messages
WHERE content LIKE '% 关注了你';

DELETE c
FROM conversations c
LEFT JOIN messages m ON m.conversation_id = c.id
WHERE m.id IS NULL;
