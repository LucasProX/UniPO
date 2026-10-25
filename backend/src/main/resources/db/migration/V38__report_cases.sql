-- O01：同一对象的多条举报归到一个处理单，审核员按处理单领取、处置。
-- open_key 只在处理单未结案时有值（如 POST:123），唯一约束保证同一对象同时只有一个未结案的单；结案后清空，
-- 之后再被举报会开新单。version 只随领取、结案推进，用于并发处置时的乐观校验。
CREATE TABLE report_cases (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_type      VARCHAR(16)  NOT NULL,
    target_id        BIGINT       NOT NULL,
    target_user_id   BIGINT       NULL,
    school_id        BIGINT       NOT NULL,
    college_id       BIGINT       NULL,
    major_id         BIGINT       NULL,
    open_key         VARCHAR(40)  NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    report_count     INT          NOT NULL DEFAULT 0,
    latest_snapshot  VARCHAR(600) NULL,
    first_reported_at DATETIME    NOT NULL,
    last_reported_at DATETIME     NOT NULL,
    assignee_id      BIGINT       NULL,
    claimed_at       DATETIME     NULL,
    resolution       VARCHAR(30)  NULL,
    resolution_note  VARCHAR(500) NULL,
    handled_by       BIGINT       NULL,
    handled_at       DATETIME     NULL,
    version          INT          NOT NULL DEFAULT 0,
    created_at       DATETIME     NOT NULL,
    UNIQUE KEY uk_cases_open (open_key),
    KEY idx_cases_queue (status, school_id, last_reported_at),
    KEY idx_cases_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE content_reports ADD COLUMN case_id BIGINT NULL, ADD KEY idx_reports_case (case_id);

-- 已有的待处理举报按对象归成处理单。
INSERT INTO report_cases (target_type, target_id, target_user_id, school_id, college_id, major_id, open_key, status,
                          report_count, latest_snapshot, first_reported_at, last_reported_at, created_at)
SELECT target_type, target_id, MAX(target_user_id), MAX(school_id), MAX(college_id), MAX(major_id),
       CONCAT(target_type, ':', target_id), 'PENDING', COUNT(*), MAX(snapshot), MIN(created_at), MAX(created_at), NOW()
FROM content_reports WHERE status = 'PENDING'
GROUP BY target_type, target_id;

UPDATE content_reports r
JOIN report_cases c ON c.target_type = r.target_type AND c.target_id = r.target_id AND c.open_key IS NOT NULL
SET r.case_id = c.id
WHERE r.status = 'PENDING';
