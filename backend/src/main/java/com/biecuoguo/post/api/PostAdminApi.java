package com.biecuoguo.post.api;

import java.util.List;

/**
 * 管理端的帖子写操作（ADR-0002）。
 *
 * <p>和 {@link PostQueryApi} 分开放：查询是给所有模块用的，审核/下架只给后台用。
 * 混在一个接口里会让「用户资料页能下架帖子」这种误用成为可能。
 */
public interface PostAdminApi {

    /** 条件搜索；status 为空表示全部，limit 最大 100。只返回 actor 授权范围内的帖子。 */
    List<PostDtos.PostView> search(String keyword, String status, int limit, com.biecuoguo.security.CurrentUser actor);

    /**
     * 部分更新：只改传入的字段。status 只允许 published / removed / draft。
     * 改 status 需要 post.moderate、改置顶需要 post.feature、改官方和别错过需要 post.official，都按帖子所在范围判断。
     */
    PostDtos.PostView moderate(long postId, Boolean pinned, Boolean official, Boolean dontMiss, String status,
                               com.biecuoguo.security.CurrentUser actor);

    /**
     * 隐藏一条评论（举报处置）：要求对评论所在帖子有下架权限；同步扣减帖子评论数和父评论回复数。已隐藏的直接返回。
     */
    void hideComment(long commentId, com.biecuoguo.security.CurrentUser actor);

    /** 被处置对象的当前状态（POST 为帖子状态，COMMENT 为评论状态）；不存在为空。 */
    java.util.Optional<String> targetStatus(String type, long id);

    /** 帖子所在的权限范围（不论状态）；帖子不存在为空。 */
    java.util.Optional<com.biecuoguo.permission.api.ScopeTarget> scopeOf(long postId);

    /** 后台查看：不论状态与学校，按传入顺序返回存在的帖子。 */
    List<PostDtos.PostView> byIds(List<Long> postIds);

    /** 设置核实日期与有效期（整体替换，传空即清除），与必需审计在同一事务里提交。 */
    PostDtos.PostView setValidity(long postId, java.time.LocalDate verifiedAt, java.time.OffsetDateTime expiresAt,
                                  com.biecuoguo.security.CurrentUser actor);
}
