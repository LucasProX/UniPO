package com.biecuoguo.wallet.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WalletServiceTest {
    @Test
    void dailyCapGrantsOnlyTheRemainingAllowance() {
        assertEquals(20, WalletService.capped(20, 0));
        assertEquals(10, WalletService.capped(20, WalletService.DAILY_EARN_CAP - 10));
        assertEquals(0, WalletService.capped(20, WalletService.DAILY_EARN_CAP));
        assertEquals(0, WalletService.capped(20, WalletService.DAILY_EARN_CAP + 5));
    }
}
