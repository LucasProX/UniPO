package com.biecuoguo.admin.internal;

import com.biecuoguo.admin.api.AdminAuditApi;
import com.biecuoguo.post.api.PostAdminApi;
import com.biecuoguo.post.api.PostDtos;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.web.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 后台帖子审核：列表按关键词/状态筛选，单个帖子做置顶、官方、免错过、上下架。
 *
 * <p>写操作全部记审计——「谁把这条帖子下架了」必须能查。
 */
@RestController
@RequestMapping("/api/admin/posts")
public class AdminPostController {

    public record ModerateRequest(Boolean pinned, Boolean official, Boolean dontMiss, String status) {
    }

    /** 整体替换：verifiedAt 为空表示未核实，expiresAt 为空表示长期有效。 */
    public record ValidityRequest(java.time.LocalDate verifiedAt, java.time.OffsetDateTime expiresAt) {
    }

    private final PostAdminApi postAdminApi;
    private final AdminAuditApi auditApi;

    public AdminPostController(PostAdminApi postAdminApi, AdminAuditApi auditApi) {
        this.postAdminApi = postAdminApi;
        this.auditApi = auditApi;
    }

    @GetMapping
    public ApiResponse<List<PostDtos.PostView>> search(@RequestParam(required = false) String keyword,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(postAdminApi.search(keyword, status, limit, SecurityUtils.currentUser()));
    }

    @PatchMapping("/{id}")
    public ApiResponse<PostDtos.PostView> moderate(@PathVariable Long id, @RequestBody ModerateRequest request) {
        PostDtos.PostView view = postAdminApi.moderate(id, request.pinned(), request.official(),
                request.dontMiss(), request.status(), SecurityUtils.currentUser());
        auditApi.record(SecurityUtils.currentUser(), "post.moderate", "post", id,
                "status=" + view.status() + " pinned=" + view.pinned()
                        + " official=" + view.official() + " dontMiss=" + view.dontMiss());
        return ApiResponse.ok(view);
    }

    /** 攻略有效期（G06）：过期后不再作为任务的有效攻略，帖子本身照常可读。 */
    @PutMapping("/{id}/validity")
    public ApiResponse<PostDtos.PostView> validity(@PathVariable Long id, @RequestBody ValidityRequest request) {
        return ApiResponse.ok(postAdminApi.setValidity(id, request.verifiedAt(), request.expiresAt(),
                SecurityUtils.currentUser()));
    }
}
