-- 成长背景：入学年份决定当前学期与阶段，学校/学院/专业沿用 V1/V3 的列。
ALTER TABLE users ADD COLUMN entry_year SMALLINT NULL COMMENT '入学年份，成长阶段推算依据；为空表示尚未补充';

-- 阶段适用的学期区间（0 = 大一上，1 = 大一下……）；term_to 为空表示之后一直适用。
ALTER TABLE achievement_stages
    ADD COLUMN term_from INT NULL COMMENT '起始学期序号，0 为大一上',
    ADD COLUMN term_to INT NULL COMMENT '结束学期序号（含），为空表示不设上限';

UPDATE achievement_stages SET term_from = 0, term_to = 0 WHERE code = 'freshman-1';
UPDATE achievement_stages SET term_from = 1, term_to = 1 WHERE code = 'freshman-2';
UPDATE achievement_stages SET term_from = 2, term_to = 3 WHERE code = 'sophomore';
UPDATE achievement_stages SET term_from = 4, term_to = 5 WHERE code = 'junior';
UPDATE achievement_stages SET term_from = 6, term_to = NULL WHERE code = 'senior';
