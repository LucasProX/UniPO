package com.biecuoguo.permission.api;

/** 能力清单。角色是这些能力的固定组合（见 {@link Role}）；新增能力只往这里加，不改已有名字的含义。 */
public enum Capability {
    /** 发官方帖（带官方标识）、标「别错过」。 */
    POST_OFFICIAL("post.official"),
    /** 置顶帖子。 */
    POST_FEATURE("post.feature"),
    /** 下架、恢复帖子。 */
    POST_MODERATE("post.moderate"),
    /** 设置攻略的核验日与有效期。 */
    POST_VALIDITY("post.validity"),
    /** 发布带奖励的活动，奖励上限是角色属性。 */
    ACTIVITY_PUBLISH("activity.publish"),
    /** 管理范围内的活动：审核报名、签到码、补签、撤销签到、编辑、取消。 */
    ACTIVITY_MANAGE("activity.manage"),
    /** 维护任务与攻略的关联。 */
    GUIDE_MANAGE("guide.manage"),
    /** 受理、处理举报（O01）。 */
    REPORT_HANDLE("report.handle");

    private final String code;

    Capability(String code) {
        this.code = code;
    }

    /** 对外的稳定名字，客户端按它判断入口。 */
    public String code() {
        return code;
    }
}
