package com.biecuoguo.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SocialRelationshipLockMapper {

    @Insert("""
            INSERT INTO social_relationship_pair_locks (user_low_id, user_high_id)
            VALUES (#{lowUserId}, #{highUserId})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int ensurePair(@Param("lowUserId") long lowUserId, @Param("highUserId") long highUserId);

    @Select("""
            SELECT id FROM social_relationship_pair_locks
            WHERE user_low_id = #{lowUserId} AND user_high_id = #{highUserId}
            FOR UPDATE
            """)
    Long lockPair(@Param("lowUserId") long lowUserId, @Param("highUserId") long highUserId);
}
