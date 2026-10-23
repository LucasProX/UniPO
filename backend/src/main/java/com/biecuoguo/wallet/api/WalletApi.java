package com.biecuoguo.wallet.api;

/** 金币入账的唯一入口。调用方必须在自己的业务事务里调用，入账与业务事实一起提交或回滚。 */
public interface WalletApi {
    /**
     * 入账。相同 idempotencyKey 第二次调用不再入账并返回 0；超过每日获取上限的部分不入账。
     *
     * @return 实际入账的金币数
     */
    int credit(long userId, int amount, String sourceType, Long sourceId, String idempotencyKey, String reason);
}
