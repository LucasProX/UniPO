package com.biecuoguo.wallet.internal;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("coin_ledger")
public class CoinLedgerEntry {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Integer amount;
    private Integer balanceAfter;
    private String sourceType;
    private Long sourceId;
    private String idempotencyKey;
    private String reason;
    private LocalDateTime createdAt;
}
