package com.biecuoguo.wallet.api;

import java.time.OffsetDateTime;
import java.util.List;

public final class WalletDtos {
    private WalletDtos() {}

    public record LedgerEntry(long id, int amount, int balanceAfter, String sourceType, Long sourceId,
                              String reason, OffsetDateTime createdAt) {}

    /** 今日已获取与每日上限按 Asia/Shanghai 自然日计算。 */
    public record WalletView(int balance, int earnedToday, int dailyCap, List<LedgerEntry> entries,
                             String nextCursor, boolean hasMore) {}
}
