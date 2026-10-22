package com.biecuoguo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.biecuoguo.domain.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface UserMapper extends BaseMapper<User> {
    /** Reward writers lock before inserting rows that reference the actor, avoiding FK lock upgrades. */
    @Select("SELECT * FROM users WHERE id = #{id} FOR UPDATE")
    User lockById(@Param("id") long id);
}
