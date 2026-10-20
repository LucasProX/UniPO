-- 新用户资料完善（2026-10-20）
-- 手机号/邮箱新注册用户 onboarding_completed=false，首次登录走 3 步引导；
-- 已有用户默认 true，不被强制打断。

ALTER TABLE users
    ADD COLUMN gender VARCHAR(10) NULL,
    ADD COLUMN birth_year INT NULL,
    ADD COLUMN zodiac VARCHAR(10) NULL,
    ADD COLUMN mbti VARCHAR(8) NULL,
    ADD COLUMN show_gender TINYINT(1) NOT NULL DEFAULT 1,
    ADD COLUMN show_age TINYINT(1) NOT NULL DEFAULT 1,
    ADD COLUMN show_grade TINYINT(1) NOT NULL DEFAULT 1,
    ADD COLUMN onboarding_completed TINYINT(1) NOT NULL DEFAULT 1;
