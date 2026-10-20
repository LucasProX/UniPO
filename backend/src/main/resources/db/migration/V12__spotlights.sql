-- 首页大屏轮播位（spotlights，2026-10-20）
--
-- 背景：首页原来有一块「校园焦点」轮播，组件化重构时被整体删掉（CSS 还留着）。
-- 现在正式建表，运营手动配置；后续接算法推荐时，只需追加 source='ALGORITHM' 的行，
-- 或在读取时由 SpotlightService 合并算法结果，公开接口 /api/spotlights 不用变。
--
-- 设计要点：
-- - source 区分 MANUAL / ALGORITHM，避免以后「运营位」和「算法位」两套接口；
-- - reference_post_id 给算法位指回帖子（手动位留空）；
-- - starts_at / ends_at 控制投放时间窗，enabled 做紧急下线；
-- - weight 越大越靠前，同权重按 id 倒序（新配置优先）。
-- - 图片先允许为空：前端用品牌渐变兜底，运营上传后走 MinIO 地址。

CREATE TABLE IF NOT EXISTS spotlights (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    title             VARCHAR(80)  NOT NULL,
    subtitle          VARCHAR(200) NULL,
    badge             VARCHAR(20)  NULL,
    image_url         VARCHAR(500) NULL,
    link_url          VARCHAR(500) NULL,
    source            VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',
    reference_post_id BIGINT       NULL,
    weight            INT          NOT NULL DEFAULT 0,
    enabled           TINYINT(1)   NOT NULL DEFAULT 1,
    starts_at         DATETIME     NULL,
    ends_at           DATETIME     NULL,
    created_by        BIGINT       NULL,
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_spotlights_active (enabled, starts_at, ends_at, weight)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO spotlights (title, subtitle, badge, link_url, source, weight, enabled, created_at, updated_at) VALUES
('今天学校里，真正有用的事。', '校 PO、院 PO、专业 PO 分层展示，重要节点不再埋在群聊里。', '校园焦点', '/', 'MANUAL', 100, 1, NOW(), NOW()),
('期末周自习位实时更新', '图书馆与教学楼空位、开放时间，由同学和运营共同维护。', '重点事件', '/?board=school', 'MANUAL', 90, 1, NOW(), NOW()),
('讲座、报名、比赛集中看', '按日期筛选，点击具体日期中间信息流会自动切到当天。', '活动', '/?board=college', 'MANUAL', 80, 1, NOW(), NOW());
