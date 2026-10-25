package com.biecuoguo.permission.api;

/**
 * 被操作对象所在的范围：学校必有，学院、专业按对象自己的层级给。
 * 例如面向全校的活动只有 schoolId；面向某专业的活动三者都有（学院是专业所属学院）。
 */
public record ScopeTarget(long schoolId, Long collegeId, Long majorId) {
    public static ScopeTarget school(long schoolId) {
        return new ScopeTarget(schoolId, null, null);
    }

    public static ScopeTarget college(long schoolId, long collegeId) {
        return new ScopeTarget(schoolId, collegeId, null);
    }

    public static ScopeTarget major(long schoolId, Long collegeId, long majorId) {
        return new ScopeTarget(schoolId, collegeId, majorId);
    }
}
