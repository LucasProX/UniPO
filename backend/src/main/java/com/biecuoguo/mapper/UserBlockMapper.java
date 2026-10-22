package com.biecuoguo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.biecuoguo.domain.UserBlock;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface UserBlockMapper extends BaseMapper<UserBlock> {

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM user_blocks
                WHERE (blocker_id = #{firstId} AND blocked_id = #{secondId})
                   OR (blocker_id = #{secondId} AND blocked_id = #{firstId})
            )
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    boolean existsEitherWay(@Param("firstId") long firstId, @Param("secondId") long secondId);

    @Select("""
            SELECT CASE WHEN blocker_id = #{viewerId} THEN blocked_id ELSE blocker_id END
            FROM user_blocks WHERE blocker_id = #{viewerId} OR blocked_id = #{viewerId}
            """)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> selectBlockedUserIds(@Param("viewerId") long viewerId);
}
