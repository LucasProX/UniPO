package com.biecuoguo.permission.api;

import java.util.EnumSet;
import java.util.Set;

import static com.biecuoguo.permission.api.Capability.*;

/**
 * 角色模板：能授予在哪一级范围、带哪些能力、发活动的奖励上限。写在代码里而不是配置表里，
 * 改动要经过评审和测试。平台管理员不在这里，它是 {@code users.role = 'ADMIN'}，拥有全部能力。
 */
public enum Role {
    CONTENT_EDITOR("平台内容编辑", ScopeType.PLATFORM, 0, null, EnumSet.of(POST_OFFICIAL, POST_VALIDITY, GUIDE_MANAGE)),
    SCHOOL_OPERATOR("校运营", ScopeType.SCHOOL, 30, new RewardCap(100, 200),
            EnumSet.of(POST_OFFICIAL, POST_FEATURE, POST_MODERATE, POST_VALIDITY, ACTIVITY_PUBLISH, ACTIVITY_MANAGE,
                    GUIDE_MANAGE, REPORT_HANDLE)),
    COLLEGE_OPERATOR("院运营", ScopeType.COLLEGE, 20, new RewardCap(50, 100),
            EnumSet.of(POST_OFFICIAL, POST_MODERATE, ACTIVITY_PUBLISH, ACTIVITY_MANAGE, REPORT_HANDLE)),
    MAJOR_OPERATOR("专业运营", ScopeType.MAJOR, 10, new RewardCap(30, 60),
            EnumSet.of(POST_OFFICIAL, ACTIVITY_PUBLISH, ACTIVITY_MANAGE)),
    MODERATOR("审核员", ScopeType.SCHOOL, 5, null, EnumSet.of(POST_MODERATE, REPORT_HANDLE));

    /** 平台管理员发活动的上限，与校运营相同。 */
    public static final RewardCap ADMIN_REWARD_CAP = new RewardCap(100, 200);

    private final String label;
    private final ScopeType scopeType;
    private final int rank;
    private final RewardCap rewardCap;
    private final Set<Capability> capabilities;

    Role(String label, ScopeType scopeType, int rank, RewardCap rewardCap, Set<Capability> capabilities) {
        this.label = label;
        this.scopeType = scopeType;
        this.rank = rank;
        this.rewardCap = rewardCap;
        this.capabilities = capabilities;
    }

    public String label() { return label; }

    /** 这个角色只能授予在这一级范围上。 */
    public ScopeType scopeType() { return scopeType; }

    /** 展示和挑选「主要身份」用的高低顺序，越大越高。 */
    public int rank() { return rank; }

    /** 发活动的奖励上限；不能发活动的角色为 null。 */
    public RewardCap rewardCap() { return rewardCap; }

    public boolean has(Capability capability) { return capabilities.contains(capability); }

    public Set<Capability> capabilities() { return capabilities; }

    /** 帖子作者、活动组织者旁展示的旧 role 字段（兼容 /api/v1 契约），审核员和内容编辑不展示。 */
    public String displayRole() {
        return switch (this) {
            case SCHOOL_OPERATOR, COLLEGE_OPERATOR, MAJOR_OPERATOR -> name();
            default -> null;
        };
    }

    public String displayScope() {
        return switch (this) {
            case SCHOOL_OPERATOR -> "school";
            case COLLEGE_OPERATOR -> "college";
            case MAJOR_OPERATOR -> "major";
            default -> null;
        };
    }
}
