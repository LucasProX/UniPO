package com.biecuoguo.post.api;

import com.biecuoguo.security.CurrentUser;

import java.util.List;

/**
 * post 模块对外提供的只读查询（ADR-0002）。
 *
 * <p>为什么需要它：个人主页统计、后台概览、用户资料页都需要读帖子数据，
 * 但它们不应该直接注入 PostMapper / CommentMapper —— 那会让 post 模块的实体和表结构
 * 变成全局共享，永远拆不出去。这里只暴露「问题」，不暴露「表」。
 *
 * <p>实现是 {@code post.internal.PostService}；调用方（UserService / AdminService / 用户相关 Controller）
 * 只依赖这个接口，post 内部怎么查、查几张表都不关它们的事。
 */
public interface PostQueryApi {

    /** 个人主页统计里与帖子相关的部分；following/followers 仍归 user 模块。 */
    record UserPostStats(long posts, long comments, long likes, long favorites, long shares) {
    }

    /** 后台概览需要的总量。 */
    record AdminPostStats(long posts, long publishedPosts, long comments) {
    }

    /** 任务攻略：有效的帖子（按传入顺序）与被隐藏的过期帖数量。不可见（别校、下架、拉黑）的不计数。 */
    record GuideSelection(List<PostDtos.PostView> posts, int expiredCount) {
    }

    UserPostStats statsForUser(long userId);

    /** 与某个活动（kind=activity）或主线任务（kind=task）关联的、viewer 可见的帖子，最新在前。 */
    List<PostDtos.PostView> linkedPosts(String kind, long targetId, CurrentUser viewer, int limit);

    /** 按 viewer 的可见性筛出未过期的攻略帖，保持 postIds 的顺序。 */
    GuideSelection guidePosts(List<Long> postIds, CurrentUser viewer);

    AdminPostStats adminStats();

    List<PostDtos.PostView> userPosts(Long userId, CurrentUser viewer);

    List<PostDtos.PostView> userPostsByUid(String uid, CurrentUser viewer);

    List<PostDtos.PostView> likedPosts(CurrentUser viewer);

    List<PostDtos.PostView> likedPostsByUid(String uid, CurrentUser viewer);

    List<PostDtos.PostView> favoritePosts(CurrentUser viewer);

    List<PostDtos.PostView> favoritePostsByUid(String uid, CurrentUser viewer);

    /** 被举报对象的必要信息：作者、所在范围和举报当时的内容摘录。 */
    record ReportableContent(long id, long authorId, com.biecuoguo.permission.api.ScopeTarget scope, String snapshot) {}

    /**
     * viewer 能看到的帖子或评论（type 为 POST / COMMENT）；看不到的一律为空，举报接口据此返回 404，
     * 不暴露别校或已拉黑对象是否存在。
     */
    java.util.Optional<ReportableContent> reportable(String type, long id, CurrentUser viewer);
}
