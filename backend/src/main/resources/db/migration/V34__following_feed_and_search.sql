-- 关注流：按作者取最新帖子。
ALTER TABLE posts ADD KEY idx_posts_author_published (author_id, status, published_at, id);

-- 搜索：ngram 分词让中文按两字切词；查询词至少两个字（ngram_token_size 默认 2）。
-- InnoDB 一次只能建一个 FULLTEXT 索引，所以分两条语句。
ALTER TABLE posts ADD FULLTEXT INDEX ft_posts_text (title, content, tags) WITH PARSER ngram;
ALTER TABLE users ADD FULLTEXT INDEX ft_users_nickname (nickname) WITH PARSER ngram;
