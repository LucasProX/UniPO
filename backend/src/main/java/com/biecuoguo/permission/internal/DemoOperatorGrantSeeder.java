package com.biecuoguo.permission.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.config.AppProperties;
import com.biecuoguo.domain.User;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.permission.api.Role;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 只在 app.bootstrap.seed-demo-data 打开时运行：给 AuthService 建好的两个演示运营补上授权，
 * 否则 V36 之后它们只有展示用的 role 字段，发不了活动。应用就绪时运行，保证在 AuthService 的 CommandLineRunner 之后。
 */
@Component
public class DemoOperatorGrantSeeder {
    private final AppProperties properties;
    private final UserMapper userMapper;
    private final RoleGrantService grants;

    public DemoOperatorGrantSeeder(AppProperties properties, UserMapper userMapper, RoleGrantService grants) {
        this.properties = properties;
        this.userMapper = userMapper;
        this.grants = grants;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (properties.bootstrap() == null || !properties.bootstrap().seedDemoData()) return;
        seed("24052002", Role.SCHOOL_OPERATOR);
        seed("24052003", Role.COLLEGE_OPERATOR);
    }

    private void seed(String publicUid, Role role) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPublicUid, publicUid));
        if (user == null) return;
        Long scopeId = role == Role.SCHOOL_OPERATOR ? user.getSchoolId() : user.getCollegeId();
        if (scopeId != null) grants.ensureSeedGrant(user.getId(), role, scopeId);
    }
}
