-- G05：主线任务详情。步骤、期限提示、来源与复核日期、内容版本；完成记录保留完成时的版本。
-- 来源如实标注：通用主线由平台编辑整理，不冒充学校官方；学校校历和官方通知接入后（G01/G02）按校覆盖。

ALTER TABLE achievement_tasks
    ADD COLUMN steps VARCHAR(1000) NULL COMMENT '操作步骤，每行一步',
    ADD COLUMN due_hint VARCHAR(60) NULL COMMENT '期限提示，如「开学第 2 周内」；具体日期以学校通知为准',
    ADD COLUMN source_type VARCHAR(20) NOT NULL DEFAULT 'PLATFORM' COMMENT 'PLATFORM 平台整理 / SCHOOL 学校官方 / COMMUNITY 同学经验',
    ADD COLUMN source_name VARCHAR(80) NOT NULL DEFAULT 'UniPO 编辑部整理',
    ADD COLUMN source_url VARCHAR(500) NULL,
    ADD COLUMN reviewed_at DATE NULL COMMENT '内容最近一次复核日期',
    ADD COLUMN version INT NOT NULL DEFAULT 1 COMMENT '内容版本，改步骤或期限时 +1',
    ADD COLUMN updated_at DATETIME NULL;

ALTER TABLE achievement_progress
    ADD COLUMN task_version INT NOT NULL DEFAULT 1 COMMENT '完成时的任务内容版本';

UPDATE achievement_tasks SET reviewed_at = '2026-10-23', updated_at = NOW();

UPDATE achievement_tasks SET due_hint = '入学报到当天',
    steps = '查看录取通知书里的报到须知，准备好身份证、通知书和照片\n确认自己学院的报到地点与时间\n按须知完成缴费或绿色通道手续\n领取宿舍钥匙并入住'
WHERE title = '完成报到';
UPDATE achievement_tasks SET due_hint = '开学第 1 周',
    steps = '按学校通知领取校园卡\n在官方渠道完成激活和首次充值\n试刷一次食堂、图书馆或门禁\n挂失方式记下来，丢卡第一时间处理'
WHERE title = '激活校园卡';
UPDATE achievement_tasks SET due_hint = '退改选截止前',
    steps = '登录教务系统查看已选课程\n对照培养方案确认必修课没有漏选\n需要调整的在退改选时间窗内完成\n截图保存最终课表'
WHERE title = '确认本学期选课';
UPDATE achievement_tasks SET due_hint = '以学校教务通知的报名时间为准',
    steps = '关注教务处或学院发布的报名通知\n确认报名资格和缴费方式\n在报名系统完成报名并缴费\n核对报名状态是否为「已缴费」'
WHERE title = '四级报名';
UPDATE achievement_tasks SET due_hint = '社团招新期间（通常在开学后 2 至 4 周）',
    steps = '看一遍社团招新信息，挑一到三个感兴趣的\n按要求报名或提交材料\n准时参加面试\n记录结果和下一步安排'
WHERE title = '参加一次社团面试';
UPDATE achievement_tasks SET due_hint = '期末考试前 4 周',
    steps = '列出本学期所有考试科目和考试时间\n按难度和学分排优先级\n给每门课分配复习时间段\n每周回顾一次进度'
WHERE title = '写一份期末复习计划';
UPDATE achievement_tasks SET due_hint = '以学校转专业通知为准',
    steps = '查找学校当年的转专业实施办法\n确认目标专业的成绩和名额要求\n记下申请时间窗和所需材料\n需要的话找辅导员或学长学姐了解经验'
WHERE title = '了解转专业政策';
UPDATE achievement_tasks SET due_hint = '大二学年内',
    steps = '了解学院常见的竞赛和开放实验室\n选一个方向，找到报名入口或联系老师\n完成报名或进组\n记录参与内容，后面写进简历'
WHERE title = '参加一次竞赛或实验室';
UPDATE achievement_tasks SET due_hint = '大二下学期前',
    steps = '选一个简洁的简历模板\n写清教育经历和主修课程\n补上项目、竞赛、社团和实践经历\n请老师或学长学姐帮忙看一遍'
WHERE title = '整理一份简历初稿';
UPDATE achievement_tasks SET due_hint = '大三学年内',
    steps = '确定想去的行业和岗位类型\n根据岗位要求调整简历\n通过校招、招聘平台或内推投递\n记录投递进度，按时准备笔试和面试'
WHERE title = '完成一次实习投递';
UPDATE achievement_tasks SET due_hint = '大三下学期前',
    steps = '分别了解求职、考研和考公的时间线\n结合成绩、兴趣和家庭情况做比较\n和老师或已经上岸的学长学姐聊一次\n定下主攻方向和备选方案'
WHERE title = '确定毕业方向';
UPDATE achievement_tasks SET due_hint = '毕业前',
    steps = '按主攻方向推进投递、复试或考试\n关注录用或录取结果\n确认签约、录取或报到手续\n记得把好消息分享给同学'
WHERE title = '拿到一个 Offer 或录取';
