package com.biecuoguo.permission.api;

/** 授权范围的层级：平台 ⊃ 学校 ⊃ 学院 ⊃ 专业。组织（ORG）在第二期加入。 */
public enum ScopeType {
    PLATFORM, SCHOOL, COLLEGE, MAJOR
}
