-- 统一的举报表（F01）：举报用户、帖子、评论都进这里，内容过滤命中的可疑评论也以系统来源进来（reporter_id 为空）。
-- 带上被举报对象所在的学校 / 学院 / 专业，O01 处理时按审核员的授权范围过滤。
-- snapshot 是举报那一刻的内容摘录，作者之后改了或删了，证据还在；只给处理人看，不回给举报人。
CREATE TABLE content_reports (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    reporter_id    BIGINT        NULL,
    source         VARCHAR(16)   NOT NULL,
    target_type    VARCHAR(16)   NOT NULL,
    target_id      BIGINT        NOT NULL,
    target_user_id BIGINT        NULL,
    school_id      BIGINT        NOT NULL,
    college_id     BIGINT        NULL,
    major_id       BIGINT        NULL,
    category       VARCHAR(30)   NOT NULL,
    detail         VARCHAR(500)  NULL,
    snapshot       VARCHAR(600)  NULL,
    status         VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    handled_by     BIGINT        NULL,
    handled_at     DATETIME      NULL,
    resolution     VARCHAR(30)   NULL,
    handle_note    VARCHAR(500)  NULL,
    created_at     DATETIME      NOT NULL,
    UNIQUE KEY uk_reports_reporter_target (reporter_id, target_type, target_id),
    KEY idx_reports_queue (status, school_id, created_at),
    KEY idx_reports_target (target_type, target_id),
    CONSTRAINT fk_reports_reporter FOREIGN KEY (reporter_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 旧的用户举报搬过来，之后只写新表；user_reports 保留不删，方便核对。
INSERT IGNORE INTO content_reports (reporter_id, source, target_type, target_id, target_user_id, school_id,
                                    category, detail, snapshot, status, created_at)
SELECT r.reporter_id, 'USER', 'USER', r.target_user_id, r.target_user_id, COALESCE(u.school_id, 1),
       r.category, r.detail, LEFT(u.nickname, 600), UPPER(r.status), r.created_at
FROM user_reports r JOIN users u ON u.id = r.target_user_id;
