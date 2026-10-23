package com.biecuoguo.wallet.internal;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface WalletMapper extends BaseMapper<CoinLedgerEntry> {

    @Insert("INSERT IGNORE INTO user_wallets (user_id, balance, updated_at) VALUES (#{userId}, 0, #{now})")
    int ensureWallet(@Param("userId") long userId, @Param("now") LocalDateTime now);

    @Select("SELECT balance FROM user_wallets WHERE user_id = #{userId} FOR UPDATE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Integer lockBalance(@Param("userId") long userId);

    @Select("SELECT balance FROM user_wallets WHERE user_id = #{userId}")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    Integer balance(@Param("userId") long userId);

    @Update("UPDATE user_wallets SET balance = #{balance}, updated_at = #{now} WHERE user_id = #{userId}")
    int updateBalance(@Param("userId") long userId, @Param("balance") int balance, @Param("now") LocalDateTime now);

    @Select("SELECT COUNT(*) FROM coin_ledger WHERE idempotency_key = #{key}")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    int countKey(@Param("key") String key);

    @Select("""
            SELECT COALESCE(SUM(amount), 0) FROM coin_ledger
            WHERE user_id = #{userId} AND amount > 0 AND created_at >= #{from} AND created_at < #{to}
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    int earnedBetween(@Param("userId") long userId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("""
            <script>
            SELECT * FROM coin_ledger WHERE user_id = #{userId}
              <if test="beforeId != null">AND id &lt; #{beforeId}</if>
            ORDER BY id DESC LIMIT #{limit}
            </script>
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<CoinLedgerEntry> page(@Param("userId") long userId, @Param("beforeId") Long beforeId, @Param("limit") int limit);
}
