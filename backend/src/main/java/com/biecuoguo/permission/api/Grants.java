package com.biecuoguo.permission.api;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 某个用户此刻拥有的全部权限：平台管理员标记 + 有效授权。一次请求取一次，之后的判断都是纯计算。
 * 默认拒绝：没有授权包含目标就是没有权限。
 */
public final class Grants {
    private static final Grants NONE = new Grants(false, List.of());

    private final boolean platformAdmin;
    private final List<Grant> grants;

    public Grants(boolean platformAdmin, List<Grant> grants) {
        this.platformAdmin = platformAdmin;
        this.grants = List.copyOf(grants);
    }

    public static Grants none() {
        return NONE;
    }

    public boolean platformAdmin() { return platformAdmin; }

    public List<Grant> all() { return grants; }

    /** 平台管理员或至少一条有效授权：能进入 /api/admin/** 的前提。 */
    public boolean any() {
        return platformAdmin || !grants.isEmpty();
    }

    public boolean can(Capability capability, ScopeTarget target) {
        if (platformAdmin) return true;
        return grants.stream().anyMatch(g -> g.role().has(capability) && g.contains(target));
    }

    /** 有这个能力的授权（不论范围），用于后台列表按范围过滤。 */
    public List<Grant> with(Capability capability) {
        return grants.stream().filter(g -> g.role().has(capability)).toList();
    }

    public boolean hasAnywhere(Capability capability) {
        return platformAdmin || grants.stream().anyMatch(g -> g.role().has(capability));
    }

    /** 能面向目标发活动时的奖励上限（多条授权取最大）；不能发则为空。 */
    public Optional<RewardCap> rewardCap(ScopeTarget target) {
        if (platformAdmin) return Optional.of(Role.ADMIN_REWARD_CAP);
        return grants.stream()
                .filter(g -> g.role().has(Capability.ACTIVITY_PUBLISH) && g.role().rewardCap() != null && g.contains(target))
                .map(g -> g.role().rewardCap())
                .reduce(RewardCap::max);
    }

    /** 能发活动的授权里级别最高的一条，用来决定发布页的默认范围。 */
    public Optional<Grant> primaryPublisher() {
        return grants.stream()
                .filter(g -> g.role().has(Capability.ACTIVITY_PUBLISH))
                .max(Comparator.comparingInt((Grant g) -> g.role().rank()).thenComparing(Grant::id, Comparator.reverseOrder()));
    }

    /** 级别最高的授权，用于展示旧的 role / operatorScope 字段。 */
    public Optional<Grant> primary() {
        return grants.stream().max(Comparator.comparingInt((Grant g) -> g.role().rank())
                .thenComparing(Grant::id, Comparator.reverseOrder()));
    }
}
