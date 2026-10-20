package com.biecuoguo.post.api;

import java.util.List;

/**
 * 管理端的帖子写操作（ADR-0002）。
 *
 * <p>和 {@link PostQueryApi} 分开放：查询是给所有模块用的，审核/下架只给后台用。
 * 混在一个接口里会让「用户资料页能下架帖子」这种误用成为可能。
 */
public interface PostAdminApi {

    /** 条件搜索；status 为空表示全部，limit 最大 100。 */
    List<PostDtos.PostView> search(String keyword, String status, int limit);

    /** 部分更新：只改传入的字段。status 只允许 published / removed / draft。 */
    PostDtos.PostView moderate(long postId, Boolean pinned, Boolean official, Boolean dontMiss, String status);
}
