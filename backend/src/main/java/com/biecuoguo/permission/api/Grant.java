package com.biecuoguo.permission.api;

import java.time.LocalDateTime;
import java.util.Objects;

/** 一条有效授权。scopeId 在 PLATFORM 时为空；schoolId 是范围所属学校（PLATFORM 为空）。 */
public record Grant(long id, Role role, ScopeType scopeType, Long scopeId, Long schoolId, LocalDateTime expiresAt) {

    /** 授权范围是否包含目标：学校包含其下所有学院与专业，学院包含其下专业。 */
    public boolean contains(ScopeTarget target) {
        return switch (scopeType) {
            case PLATFORM -> true;
            case SCHOOL -> Objects.equals(scopeId, target.schoolId());
            case COLLEGE -> target.collegeId() != null && Objects.equals(scopeId, target.collegeId())
                    && Objects.equals(schoolId, target.schoolId());
            case MAJOR -> target.majorId() != null && Objects.equals(scopeId, target.majorId())
                    && Objects.equals(schoolId, target.schoolId());
        };
    }
}
