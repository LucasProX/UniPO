-- 范围授权：某人在某个学校 / 学院 / 专业 / 平台担任某个角色。权限只认这里，不再认用户资料里的学校和学院。
-- 撤销只写 revoked_*，不删行，历史可追溯。平台管理员仍是 users.role = 'ADMIN'。
CREATE TABLE role_grants (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    role          VARCHAR(32)  NOT NULL,
    scope_type    VARCHAR(16)  NOT NULL,
    scope_id      BIGINT       NULL,
    school_id     BIGINT       NULL,
    granted_by    BIGINT       NULL,
    reason        VARCHAR(200) NOT NULL,
    starts_at     DATETIME     NOT NULL,
    expires_at    DATETIME     NULL,
    revoked_at    DATETIME     NULL,
    revoked_by    BIGINT       NULL,
    revoke_reason VARCHAR(200) NULL,
    created_at    DATETIME     NOT NULL,
    KEY idx_grants_user (user_id, revoked_at, expires_at),
    KEY idx_grants_scope (scope_type, scope_id, role),
    CONSTRAINT fk_grants_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 把现有运营迁过来：按迁移这一刻资料里的学校 / 学院 / 专业定范围，之后用户改资料不再影响授权。
-- 判断依据和原来的 ActivityRules.levelOf 一致：非 USER、非 ADMIN，且 operator_scope 为 school / college / major。
-- 没有范围的泛运营（operator_scope 为空或其他值）不迁，由管理员确认后再授予。迁移来的授权不设到期时间。
INSERT INTO role_grants (user_id, role, scope_type, scope_id, school_id, granted_by, reason, starts_at, created_at)
SELECT u.id, 'SCHOOL_OPERATOR', 'SCHOOL', u.school_id, u.school_id, NULL, '历史数据迁移（V36）', NOW(), NOW()
FROM users u JOIN schools s ON s.id = u.school_id
WHERE u.role NOT IN ('USER', 'ADMIN') AND u.operator_scope = 'school' AND u.status = 'active';

INSERT INTO role_grants (user_id, role, scope_type, scope_id, school_id, granted_by, reason, starts_at, created_at)
SELECT u.id, 'COLLEGE_OPERATOR', 'COLLEGE', u.college_id, c.school_id, NULL, '历史数据迁移（V36）', NOW(), NOW()
FROM users u JOIN colleges c ON c.id = u.college_id
WHERE u.role NOT IN ('USER', 'ADMIN') AND u.operator_scope = 'college' AND u.status = 'active';

INSERT INTO role_grants (user_id, role, scope_type, scope_id, school_id, granted_by, reason, starts_at, created_at)
SELECT u.id, 'MAJOR_OPERATOR', 'MAJOR', u.major_id, m.school_id, NULL, '历史数据迁移（V36）', NOW(), NOW()
FROM users u JOIN majors m ON m.id = u.major_id
WHERE u.role NOT IN ('USER', 'ADMIN') AND u.operator_scope = 'major' AND u.status = 'active';
