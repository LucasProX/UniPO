-- 联动：帖子可以挂一个校园活动或一个主线任务；关注的人发帖时给粉丝发站内通知。
-- 不加外键：活动、任务、帖子分属不同模块，引用由各自的 api 校验；被引用方下架或取消时帖子照常可读。
ALTER TABLE posts
    ADD COLUMN activity_id BIGINT NULL COMMENT '关联的校园活动',
    ADD COLUMN task_id BIGINT NULL COMMENT '关联的主线任务',
    ADD KEY idx_posts_activity (activity_id, status, published_at),
    ADD KEY idx_posts_task (task_id, status, published_at);

ALTER TABLE user_notifications
    ADD COLUMN post_id BIGINT NULL COMMENT '与帖子相关的通知（关注的人发帖等）';
