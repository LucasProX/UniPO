package com.biecuoguo.permission.api;

/** 单场活动的奖励上限。 */
public record RewardCap(int maxXp, int maxCoins) {
    public RewardCap max(RewardCap other) {
        return other == null ? this : new RewardCap(Math.max(maxXp, other.maxXp), Math.max(maxCoins, other.maxCoins));
    }
}
