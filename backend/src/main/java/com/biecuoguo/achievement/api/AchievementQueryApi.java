package com.biecuoguo.achievement.api;

/** 成长模块读取主线与徽章的只读入口。 */
public interface AchievementQueryApi {
    AchievementDtos.AchievementView view(Long userId);

    /** 某个学期对应的主线阶段；学期序号按入学年份推算。 */
    AchievementDtos.StageView stageForTerm(Long userId, int termIndex);
}
