package com.biecuoguo.wallet.internal;

import com.biecuoguo.config.AppTime;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.wallet.api.WalletApi;
import com.biecuoguo.wallet.api.WalletDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class WalletService implements WalletApi {
    /** 每个自然日最多获取的金币；防止异常发放或刷活动。 */
    static final int DAILY_EARN_CAP = 500;

    private final WalletMapper mapper;

    public WalletService(WalletMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int credit(long userId, int amount, String sourceType, Long sourceId, String idempotencyKey, String reason) {
        if (amount <= 0) return 0;
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 80) {
            throw new IllegalArgumentException("入账幂等键不合法");
        }
        LocalDateTime now = AppTime.nowLocal();
        mapper.ensureWallet(userId, now);
        int balance = mapper.lockBalance(userId);
        // 余额行锁住之后再查幂等键：同一用户的并发入账在这里串行。
        if (mapper.countKey(idempotencyKey) > 0) return 0;
        LocalDate today = now.toLocalDate();
        int earned = mapper.earnedBetween(userId, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        int granted = capped(amount, earned);
        if (granted == 0) return 0;
        int after = balance + granted;
        CoinLedgerEntry entry = new CoinLedgerEntry();
        entry.setUserId(userId);
        entry.setAmount(granted);
        entry.setBalanceAfter(after);
        entry.setSourceType(sourceType);
        entry.setSourceId(sourceId);
        entry.setIdempotencyKey(idempotencyKey);
        entry.setReason(reason.length() > 120 ? reason.substring(0, 120) : reason);
        entry.setCreatedAt(now);
        mapper.insert(entry);
        mapper.updateBalance(userId, after, now);
        return granted;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int debit(long userId, int amount, String sourceType, Long sourceId, String idempotencyKey, String reason) {
        if (amount <= 0) return 0;
        LocalDateTime now = AppTime.nowLocal();
        mapper.ensureWallet(userId, now);
        int balance = mapper.lockBalance(userId);
        if (mapper.countKey(idempotencyKey) > 0) return 0;
        int taken = Math.min(amount, Math.max(0, balance));
        CoinLedgerEntry entry = new CoinLedgerEntry();
        entry.setUserId(userId);
        entry.setAmount(-taken);
        entry.setBalanceAfter(balance - taken);
        entry.setSourceType(sourceType);
        entry.setSourceId(sourceId);
        entry.setIdempotencyKey(idempotencyKey);
        entry.setReason(reason.length() > 120 ? reason.substring(0, 120) : reason);
        entry.setCreatedAt(now);
        mapper.insert(entry);
        mapper.updateBalance(userId, balance - taken, now);
        return taken;
    }

    static int capped(int amount, int earnedToday) {
        return Math.max(0, Math.min(amount, DAILY_EARN_CAP - earnedToday));
    }

    @Transactional(readOnly = true)
    public WalletDtos.WalletView me(CurrentUser actor, Long beforeId, Integer requestedLimit) {
        int limit = requestedLimit == null ? 20 : Math.max(1, Math.min(50, requestedLimit));
        long userId = actor.id();
        Integer balance = mapper.balance(userId);
        LocalDate today = AppTime.nowLocal().toLocalDate();
        int earned = mapper.earnedBetween(userId, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        List<CoinLedgerEntry> rows = mapper.page(userId, beforeId, limit + 1);
        boolean more = rows.size() > limit;
        List<CoinLedgerEntry> page = more ? rows.subList(0, limit) : rows;
        return new WalletDtos.WalletView(balance == null ? 0 : balance, earned, DAILY_EARN_CAP,
                page.stream().map(row -> new WalletDtos.LedgerEntry(row.getId(), row.getAmount(), row.getBalanceAfter(),
                        row.getSourceType(), row.getSourceId(), row.getReason(), AppTime.toOffset(row.getCreatedAt()))).toList(),
                more ? String.valueOf(page.getLast().getId()) : null, more);
    }
}
