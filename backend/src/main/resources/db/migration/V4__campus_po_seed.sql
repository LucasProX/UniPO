INSERT INTO colleges (id, school_id, name, code, status, created_at, updated_at) VALUES
(1, 1, '计算机学院', 'computer-science', 'active', NOW(), NOW()),
(2, 1, '设计学院', 'design', 'active', NOW(), NOW()),
(3, 1, '经济管理学院', 'business', 'active', NOW(), NOW());

INSERT INTO majors (id, school_id, college_id, name, code, status, created_at, updated_at) VALUES
(1, 1, 1, '计算机科学与技术', 'cs', 'active', NOW(), NOW()),
(2, 1, 1, '软件工程', 'software', 'active', NOW(), NOW()),
(3, 1, 2, '视觉传达设计', 'visual-design', 'active', NOW(), NOW()),
(4, 1, 3, '工商管理', 'business-admin', 'active', NOW(), NOW());

INSERT INTO users (id, public_uid, email, password_hash, nickname, avatar_url, school_id, college_id, major_id, college, major, grade, verified_status, role, operator_scope, level, xp, level_title, status, created_at, updated_at, last_login_at) VALUES
(1, '24052000', 'good@biecuoguo.local', '$2a$10$wk7CCZlbaVQAdnGxkEZd1Oa/K72SNrU04/UPlF7gXU9USuSB5nF6G', 'good', 'https://api.dicebear.com/8.x/initials/svg?seed=good', 1, 1, 1, '计算机学院', '计算机科学与技术', '大二', 'verified', 'USER', 'student', 1, 0, '新同学', 'active', NOW(), NOW(), NOW());

UPDATE users
SET college_id = 1,
    major_id = 1,
    college = '计算机学院',
    major = '计算机科学与技术',
    grade = '大二',
    level = 37,
    xp = 1460,
    level_title = '食堂测评官'
WHERE id = 1;

INSERT INTO users (id, public_uid, email, password_hash, nickname, avatar_url, school_id, college_id, major_id, college, major, grade, verified_status, role, operator_scope, level, xp, level_title, status, created_at, updated_at, last_login_at) VALUES
(2, '24052001', 'xiaopo@example.com', '$2a$10$wk7CCZlbaVQAdnGxkEZd1Oa/K72SNrU04/UPlF7gXU9USuSB5nF6G', '小坡不鸽', 'https://api.dicebear.com/8.x/initials/svg?seed=PO', 1, 1, 1, '计算机学院', '计算机科学与技术', '大三', 'verified', 'USER', 'student', 64, 2520, '校园情报员', 'active', NOW(), NOW(), NOW()),
(3, '24052002', 'school-op@example.com', '$2a$10$wk7CCZlbaVQAdnGxkEZd1Oa/K72SNrU04/UPlF7gXU9USuSB5nF6G', '校霸情报台', 'https://api.dicebear.com/8.x/initials/svg?seed=XB', 1, 1, 1, '计算机学院', '计算机科学与技术', '运营', 'verified', 'SCHOOL_OPERATOR', 'school', 88, 3560, '毕设渡劫人', 'active', NOW(), NOW(), NOW()),
(4, '24052003', 'college-op@example.com', '$2a$10$wk7CCZlbaVQAdnGxkEZd1Oa/K72SNrU04/UPlF7gXU9USuSB5nF6G', '计院院花', 'https://api.dicebear.com/8.x/initials/svg?seed=YH', 1, 1, 1, '计算机学院', '计算机科学与技术', '运营', 'verified', 'COLLEGE_OPERATOR', 'college', 72, 2880, '学分炼金术士', 'active', NOW(), NOW(), NOW());

INSERT INTO user_preferences (user_id, goal_tags, interest_categories, prefer_urgent, prefer_high_risk, prefer_same_major, email_notification_enabled, browser_notification_enabled, remind_before_hours, created_at, updated_at) VALUES
(2, '竞赛,实习,绩点', 'school,college,major', TRUE, TRUE, TRUE, FALSE, TRUE, 24, NOW(), NOW()),
(3, '官方通知,全校事项', 'school', TRUE, TRUE, FALSE, FALSE, TRUE, 24, NOW(), NOW()),
(4, '学院通知,课程事项', 'college', TRUE, TRUE, TRUE, FALSE, TRUE, 24, NOW(), NOW());

INSERT INTO posts (id, author_id, school_id, college_id, major_id, board, source_type, title, content, excerpt, cover_url, risk_level, official, dont_miss, pinned, deadline_at, miss_consequence, next_action, tags, like_count, comment_count, favorite_count, share_count, status, published_at, created_at, updated_at) VALUES
(1, 2, 1, 1, 1, 'recommend', 'student', '图书馆三楼靠窗位今天真的像开了专注结界', '早八之后去图书馆三楼，靠西侧窗户那一排插座都能用，旁边还不会被阳光直晒。期中周想找安静位置的同学可以冲。', '早八之后去图书馆三楼，靠西侧窗户那一排插座都能用，旁边还不会被阳光直晒。', 'https://images.unsplash.com/photo-1521587760476-6c12a4b040da?auto=format&fit=crop&w=900&q=80', 'normal', FALSE, FALSE, FALSE, NULL, NULL, '带电脑和水杯，上午 10 点前基本有位置。', '图书馆,自习,期中周', 428, 96, 81, 47, 'published', NOW(), NOW(), NOW()),
(2, 3, 1, 1, 1, 'school', 'official', '四六级报名明天 17:00 截止，没缴费就等于没报名', '这条是校霸提醒：系统显示已报名不代表缴费成功。今天先确认科目、考点、缴费状态，最好截图留存。', '这条是校霸提醒：系统显示已报名不代表缴费成功。', 'https://images.unsplash.com/photo-1434030216411-0b793f4b4173?auto=format&fit=crop&w=900&q=80', 'high', TRUE, TRUE, TRUE, DATE_ADD(NOW(), INTERVAL 1 DAY), '错过后通常不能补报，只能等下一轮。', '打开报名系统，确认缴费状态和考点信息。', '今日别错过,官方,考试', 286, 42, 73, 31, 'published', NOW(), NOW(), NOW()),
(3, 4, 1, 1, 1, 'college', 'official', '计院项目实训组队墙开了，别等 ddl 才找队友', '学院今年把项目实训提前到了第 12 周确认队伍。想做 AI、小程序、后端平台方向的可以先在评论区报方向。', '学院今年把项目实训提前到了第 12 周确认队伍。', 'https://images.unsplash.com/photo-1519389950473-47ba0277781c?auto=format&fit=crop&w=900&q=80', 'medium', TRUE, TRUE, FALSE, DATE_ADD(NOW(), INTERVAL 6 DAY), '错过组队窗口后只能被随机分配，方向不一定合适。', '先写清楚方向、技术栈和每周可投入时间。', '计院,组队,项目实训', 198, 57, 49, 29, 'published', NOW(), NOW(), NOW()),
(4, 2, 1, 1, 1, 'major', 'student', '数据结构实验报告别只写代码，老师真的会看复杂度分析', '上周刚被退回一次。建议每个算法后面都补时间复杂度、边界输入和截图，查重也更稳一点。', '上周刚被退回一次。建议每个算法后面都补时间复杂度、边界输入和截图。', 'https://images.unsplash.com/photo-1515879218367-8466d910aaa4?auto=format&fit=crop&w=900&q=80', 'normal', FALSE, FALSE, FALSE, DATE_ADD(NOW(), INTERVAL 3 DAY), '退回重交会挤占下一个实验时间。', '先补复杂度分析，再补三组测试截图。', '专业PO,数据结构,实验报告', 321, 58, 66, 44, 'published', NOW(), NOW(), NOW()),
(5, 2, 1, 1, 1, 'recommend', 'student', '食堂二楼新出的番茄肥牛面，适合赶课前 12 分钟解决午饭', '出餐很快，汤底偏酸甜，牛肉量比想象多。缺点是 12:20 之后队伍会突然变长。', '出餐很快，汤底偏酸甜，牛肉量比想象多。', 'https://images.unsplash.com/photo-1569718212165-3a8278d5f624?auto=format&fit=crop&w=900&q=80', 'normal', FALSE, FALSE, FALSE, NULL, NULL, '赶课可以 11:45 前去，排队最短。', '食堂,测评,午饭', 186, 34, 28, 17, 'published', NOW(), NOW(), NOW());

INSERT INTO post_images (post_id, url, sort_order, created_at) VALUES
(1, 'https://images.unsplash.com/photo-1521587760476-6c12a4b040da?auto=format&fit=crop&w=900&q=80', 0, NOW()),
(2, 'https://images.unsplash.com/photo-1434030216411-0b793f4b4173?auto=format&fit=crop&w=900&q=80', 0, NOW()),
(3, 'https://images.unsplash.com/photo-1519389950473-47ba0277781c?auto=format&fit=crop&w=900&q=80', 0, NOW()),
(4, 'https://images.unsplash.com/photo-1515879218367-8466d910aaa4?auto=format&fit=crop&w=900&q=80', 0, NOW()),
(5, 'https://images.unsplash.com/photo-1569718212165-3a8278d5f624?auto=format&fit=crop&w=900&q=80', 0, NOW());

INSERT INTO comments (post_id, notice_id, user_id, parent_id, content, like_count, reply_count, is_featured, status, created_at, updated_at) VALUES
(1, 1, 3, NULL, '这个点位我认证，下午两点以后靠近楼梯那边会吵一点。', 31, 0, TRUE, 'visible', NOW(), NOW()),
(2, 1, 2, NULL, '补充：缴费截图一定要留，去年有人系统延迟显示很吓人。', 44, 0, TRUE, 'visible', NOW(), NOW()),
(4, 1, 4, NULL, '复杂度分析写错比不写更危险，可以先按循环层数检查一遍。', 26, 0, FALSE, 'visible', NOW(), NOW());

INSERT INTO post_daily_stats (post_id, stat_date, like_count, comment_count, share_count) VALUES
(1, CURDATE(), 120, 38, 14),
(2, CURDATE(), 86, 22, 9),
(3, CURDATE(), 76, 24, 8),
(4, CURDATE(), 145, 34, 18),
(5, CURDATE(), 66, 12, 4);

INSERT INTO user_follows (follower_id, following_id, created_at) VALUES
(1, 2, NOW()),
(2, 3, NOW()),
(2, 4, NOW());

INSERT INTO conversations (id, user_one_id, user_two_id, last_message_at, created_at, updated_at) VALUES
(1, 1, 2, NOW(), NOW(), NOW());

INSERT INTO messages (conversation_id, sender_id, receiver_id, content, is_read, created_at) VALUES
(1, 2, 1, '你刚刚收藏的那个数据结构 PO，我把模板也发评论区了。', FALSE, NOW());
