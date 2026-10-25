package com.biecuoguo.permission.api;

import com.biecuoguo.security.CurrentUser;

/** 授权的写入口。只有平台管理员能调用授予与撤销；演示数据初始化走 {@link #ensureSeedGrant}。 */
public interface RoleGrantApi {

    /**
     * 兼容旧的 {@code PUT /api/admin/users/{id}/role}：ADMIN / USER 改平台角色；运营角色撤销此人现有授权后，
     * 按其此刻资料里的学校、学院或专业重新授予。新代码请用 {@code POST /api/admin/role-grants}。
     */
    void applyLegacyRole(CurrentUser actor, long userId, String role, String operatorScope);

    /** 仅供 seed-demo-data 使用：没有同样的有效授权时补一条，不写审计。 */
    void ensureSeedGrant(long userId, Role role, Long scopeId);
}
