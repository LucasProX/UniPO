-- 删除 notices 遗留域（2026-10-20）
--
-- 背景：项目有两套内容模型 —— posts（新，Web 端在用）和 notices（遗留，前端零调用）。
-- 前端 lib/api.ts 里没有任何 /api/notices、/api/favorites、/api/reminders 调用，
-- 对应表里只有 V2 种下的 4 条演示数据，user_notice_status 一行都没有。
-- 留着它们让每个新人都要花时间确认「到底该改哪一套」，所以整体删掉。
--
-- 顺序有讲究：comments.notice_id 上有指向 notices 的外键，
-- 必须先摘外键和列，才能 DROP notices，否则 MySQL 报 1217（Cannot drop table referenced by FK）。
--
-- comments 表本身保留：帖子评论还在用（V3 把 notice_id 放开了 NULL，post_id 才是现在的正经字段）。

ALTER TABLE comments DROP FOREIGN KEY fk_comments_notice;
ALTER TABLE comments DROP INDEX idx_comments_notice;
ALTER TABLE comments DROP COLUMN notice_id;

DROP TABLE IF EXISTS notice_tags;
DROP TABLE IF EXISTS user_notice_status;
DROP TABLE IF EXISTS favorites;
DROP TABLE IF EXISTS reminders;
DROP TABLE IF EXISTS notices;