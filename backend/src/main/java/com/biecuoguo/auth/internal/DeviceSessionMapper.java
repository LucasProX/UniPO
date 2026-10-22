package com.biecuoguo.auth.internal;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface DeviceSessionMapper {
    /** The caller's pre-verification version must still be current at this atomic insertion. */
    @Insert("""
            INSERT INTO auth_device_sessions
              (session_id, user_id, auth_version, expires_at_ms, created_at_ms)
            SELECT #{sessionId}, u.id, u.auth_version, #{expiresAtMs}, #{createdAtMs}
            FROM users u
            WHERE u.id = #{userId} AND u.auth_version = #{expectedVersion} AND u.status = 'active'
            """)
    int insertIfCurrent(@Param("sessionId") String sessionId, @Param("userId") long userId,
                        @Param("expectedVersion") long expectedVersion,
                        @Param("createdAtMs") long createdAtMs, @Param("expiresAtMs") long expiresAtMs);

    /**
     * The service suspends caller transactions so every check sees a fresh committed snapshot.
     * Flush the MyBatis local cache too: no previous positive result may authorize another check.
     */
    @Select("""
            SELECT u.id AS user_id, u.email, u.role, u.auth_version, d.session_id, d.expires_at_ms
            FROM auth_device_sessions d
            JOIN users u ON u.id = d.user_id
            WHERE d.session_id = #{sessionId} AND d.user_id = #{userId}
              AND d.auth_version = #{authVersion} AND u.auth_version = d.auth_version
              AND u.status = 'active' AND d.revoked_at_ms IS NULL AND d.expires_at_ms > #{nowMs}
            """)
    @ConstructorArgs({
            @Arg(column = "user_id", javaType = Long.class),
            @Arg(column = "email", javaType = String.class),
            @Arg(column = "role", javaType = String.class),
            @Arg(column = "auth_version", javaType = Long.class),
            @Arg(column = "session_id", javaType = String.class),
            @Arg(column = "expires_at_ms", javaType = long.class)
    })
    @Options(timeout = 1, useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    DeviceSessionRecord findValid(@Param("sessionId") String sessionId, @Param("userId") long userId,
                                  @Param("authVersion") long authVersion, @Param("nowMs") long nowMs);

    @Update("""
            UPDATE auth_device_sessions SET revoked_at_ms = #{nowMs}
            WHERE session_id = #{sessionId} AND user_id = #{userId} AND revoked_at_ms IS NULL
            """)
    int revokeOne(@Param("sessionId") String sessionId, @Param("userId") long userId, @Param("nowMs") long nowMs);

    @Update("UPDATE users SET auth_version = auth_version + 1 WHERE id = #{userId}")
    int advanceUserVersion(@Param("userId") long userId);

    @Update("""
            UPDATE auth_device_sessions d JOIN users u ON u.id = d.user_id
            SET d.revoked_at_ms = #{nowMs}
            WHERE d.user_id = #{userId} AND d.auth_version < u.auth_version AND d.revoked_at_ms IS NULL
            """)
    int revokeOlderVersions(@Param("userId") long userId, @Param("nowMs") long nowMs);
}
