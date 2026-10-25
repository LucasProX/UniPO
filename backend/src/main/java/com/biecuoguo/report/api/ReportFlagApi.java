package com.biecuoguo.report.api;

import com.biecuoguo.permission.api.ScopeTarget;

/** 系统自动标记可疑内容进入举报队列（内容过滤命中 REVIEW 的评论）。只依赖 Mapper，帖子模块可以放心注入。 */
public interface ReportFlagApi {
    void flag(String targetType, long targetId, long targetUserId, ScopeTarget scope, String category, String snapshot);
}
