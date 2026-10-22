package com.biecuoguo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.biecuoguo.domain.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

public interface UserMapper extends BaseMapper<User> {
    /** Reward writers lock before inserting rows that reference the actor, avoiding FK lock upgrades. */
    @Select("SELECT * FROM users WHERE id = #{id} FOR UPDATE")
    User lockById(@Param("id") long id);

    /** Shared current read: relationship writers may run together, but cannot pass account deletion. */
    @Select("SELECT status FROM users WHERE id = #{id} FOR SHARE")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    String lockStatusForShare(@Param("id") long id);
}
