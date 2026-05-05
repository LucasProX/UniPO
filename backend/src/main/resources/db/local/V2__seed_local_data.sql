INSERT INTO schools (id, name, code, province, city, status, created_at, updated_at) VALUES
(1, '试点大学', 'pilot-university', '上海', '上海', 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO users (id, email, password_hash, nickname, avatar_url, school_id, college, major, grade, verified_status, role, status, created_at, updated_at, last_login_at) VALUES
(1, 'senior@example.com', '$2a$10$wk7CCZlbaVQAdnGxkEZd1Oa/K72SNrU04/UPlF7gXU9USuSB5nF6G', '陈同学', 'https://api.dicebear.com/8.x/initials/svg?seed=Chen', 1, '计算机学院', '计算机科学与技术', '大三', 'verified', 'USER', 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO user_preferences (user_id, goal_tags, interest_categories, prefer_urgent, prefer_high_risk, prefer_same_major, email_notification_enabled, browser_notification_enabled, remind_before_hours, created_at, updated_at) VALUES
(1, '保研,竞赛,奖学金', 'exam,scholarship,research', TRUE, TRUE, TRUE, FALSE, TRUE, 24, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO categories (id, name, code, sort_order, created_at, updated_at) VALUES
(1, '考试报名', 'exam', 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(2, '评奖评优', 'scholarship', 20, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(3, '保研考研', 'research', 30, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(4, '实习招聘', 'internship', 40, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(5, '教务事务', 'academic', 50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(6, '竞赛大创', 'competition', 60, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO tags (id, name, type, color, created_at, updated_at) VALUES
(1, '快截止', 'risk', '#FF9500', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(2, '高风险', 'risk', '#FF3B30', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(3, '影响综测', 'goal', '#34C759', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(4, '适合保研', 'goal', '#007AFF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(5, '需要材料', 'action', '#5856D6', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(6, '适合大一', 'grade', '#5AC8FA', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(7, '适合大三', 'grade', '#AF52DE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(8, '官方通知', 'source', '#6E6E73', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO notices (
  id, title, school_id, college, grade, category_id, importance, risk_level, deadline_at,
  official_url, attachment_url, summary, what_is_it, why_important, suitable_for,
  not_suitable_for, miss_consequence, next_action, materials_needed, senior_tip,
  status, is_recommended, is_pinned, is_high_risk, is_urgent, sort_weight, published_at,
  created_at, updated_at
) VALUES
(1, '2026 年上半年大学英语四六级报名确认', 1, '全校', '全体本科生', 1, 'high', 'high', TIMESTAMP '2026-05-04 17:00:00',
 'https://example.edu/cet', NULL, '四六级报名明天 17:00 截止，未确认缴费视为放弃本次考试。',
 '这是本学期四六级考试的报名确认和缴费通知。', '四六级成绩会影响保研、奖学金、部分实习网申和毕业前的个人规划。',
 '计划参加四级或六级考试、需要刷分、准备实习或保研的同学。', '本次不准备考试且已有足够成绩的同学。',
 '错过后通常不能补报，只能等下一次考试窗口。', '今天先登录报名系统确认科目、考点和缴费状态。',
 '身份证号、学号、报名系统账号、支付方式。', '高峰期系统可能卡，别压到最后一小时。缴费完成后最好截图保存。',
 'published', TRUE, TRUE, TRUE, TRUE, 80, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(2, '国家奖学金与校级奖学金材料提交', 1, '全校', '大二至大四', 2, 'high', 'high', TIMESTAMP '2026-05-08 17:00:00',
 'https://example.edu/scholarship', NULL, '奖学金材料本周五截止，需要提前准备综测证明和获奖佐证。',
 '这是本学年奖学金评审材料的提交通知。', '奖学金评审通常和综测、荣誉记录、保研材料强相关。',
 '成绩排名靠前、有竞赛/科研/志愿服务经历、准备保研的同学。', '本学年暂无评奖计划的同学。',
 '错过可能影响本学期评奖资格，部分学院不接受逾期补交。', '先确认综测分、排名证明和材料清单，缺证明今天就联系辅导员。',
 '成绩单、综测证明、获奖证书、申请表、诚信承诺书。', '材料不要只按自己理解交，最好对照学院版本清单逐项命名。',
 'published', TRUE, FALSE, TRUE, FALSE, 60, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(3, '大学生创新创业训练计划项目申报', 1, '全校', '大一至大三', 6, 'medium', 'medium', TIMESTAMP '2026-05-15 18:00:00',
 'https://example.edu/innovation', NULL, '大创项目开始申报，适合想积累科研、竞赛和保研材料的同学。',
 '这是校级/省级大学生创新创业训练计划项目申报。', '项目经历可以沉淀为论文、竞赛、简历和保研面试素材。',
 '想做科研、竞赛、保研材料积累，或者已有项目想法的同学。', '近期课业压力很大且没有稳定队友的同学。',
 '错过本轮可能要等下一学年，项目周期也会被推迟。', '先找指导老师确认方向，再组 2-4 人队伍打磨项目书。',
 '项目申报书、成员信息、指导老师确认、前期基础说明。', '最后一周再找老师通常来不及，先拿一个不完美版本去聊。',
 'published', TRUE, FALSE, FALSE, FALSE, 35, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(4, '2026 届毕业生图像采集补拍安排', 1, '全校', '大四', 5, 'medium', 'high', TIMESTAMP '2026-05-06 16:00:00',
 'https://example.edu/graduate-photo', NULL, '毕业图像采集补拍只开放一次，未采集会影响毕业材料归档。',
 '这是毕业生学历照片补拍安排。', '毕业证、学信网信息和档案材料都可能用到该照片。',
 '错过前期统一采集的大四毕业生。', '已经完成采集并确认照片无误的同学。',
 '错过补拍可能影响毕业相关材料办理，后续处理成本更高。', '今天确认自己是否在补拍名单里，并预约补拍时间。',
 '身份证、学生证、预约记录。', '拍完后记得进系统确认照片，不要只以为拍了就结束。',
 'published', FALSE, FALSE, TRUE, TRUE, 45, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO notice_tags (notice_id, tag_id) VALUES
(1, 1), (1, 2), (1, 8),
(2, 2), (2, 3), (2, 4), (2, 5),
(3, 4), (3, 5), (3, 7),
(4, 1), (4, 2), (4, 8);

INSERT INTO comments (notice_id, user_id, parent_id, content, like_count, reply_count, is_featured, status, created_at, updated_at) VALUES
(3, 1, NULL, '这个大创最好提前找老师，最后一周基本来不及。项目书不用写得很复杂，但研究目标要清楚。', 28, 0, TRUE, 'visible', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(2, 1, NULL, '奖学金材料建议按学院模板命名，证书截图也要留原件，辅导员核验会快很多。', 16, 0, TRUE, 'visible', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
