-- Optional keys identify one client write intent. NULL preserves legacy callers.
ALTER TABLE comments
  ADD COLUMN request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD UNIQUE KEY uk_comments_user_request (user_id, request_key);
ALTER TABLE post_shares
  ADD COLUMN request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN result_share_count INT NULL,
  ADD UNIQUE KEY uk_post_shares_user_request (user_id, request_key);

CREATE INDEX idx_post_likes_post ON post_likes(post_id);
CREATE INDEX idx_post_favorites_post ON post_favorites(post_id);
CREATE INDEX idx_post_shares_post ON post_shares(post_id);
CREATE INDEX idx_comment_likes_comment ON comment_likes(comment_id);
CREATE INDEX idx_comments_parent_post_status ON comments(parent_id, post_id, status);

-- Repair historical aggregates from their facts, including inflated seed counters.
UPDATE posts p SET
  like_count = (SELECT COUNT(*) FROM post_likes l WHERE l.post_id = p.id),
  favorite_count = (SELECT COUNT(*) FROM post_favorites f WHERE f.post_id = p.id),
  share_count = (SELECT COUNT(*) FROM post_shares s WHERE s.post_id = p.id),
  comment_count = (SELECT COUNT(*) FROM comments c WHERE c.post_id = p.id AND c.status <> 'hidden');

UPDATE comments c SET like_count = (SELECT COUNT(*) FROM comment_likes l WHERE l.comment_id = c.id);
-- Derived table materialisation avoids reading the updated table directly in a subquery.
UPDATE comments c LEFT JOIN (
  SELECT parent_id, post_id, COUNT(*) AS replies FROM comments
  WHERE parent_id IS NOT NULL AND status <> 'hidden' GROUP BY parent_id, post_id
) r ON r.parent_id = c.id AND r.post_id = c.post_id
SET c.reply_count = COALESCE(r.replies, 0);
