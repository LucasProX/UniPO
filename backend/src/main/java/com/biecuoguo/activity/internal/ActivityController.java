package com.biecuoguo.activity.internal;

import com.biecuoguo.activity.api.ActivityDtos;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 校园活动。浏览对本校访客开放；报名、我的活动和发布需要登录。 */
@RestController
@RequestMapping("/api/activities")
public class ActivityController {
    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping("/page")
    public ApiResponse<ActivityDtos.ActivityPage> page(@RequestParam(required = false) String tab,
                                                       @RequestParam(required = false) String cursor,
                                                       @RequestParam(required = false) Integer limit,
                                                       Authentication authentication) {
        return ApiResponse.ok(activityService.page(tab, cursor, limit, optional(authentication)));
    }

    @GetMapping("/mine")
    public ApiResponse<ActivityDtos.ActivityPage> mine(@RequestParam(required = false) String state,
                                                       @RequestParam(required = false) String cursor,
                                                       @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(activityService.mine(state, cursor, limit, SecurityUtils.currentUser()));
    }

    @GetMapping("/organizer-profile")
    public ApiResponse<ActivityDtos.OrganizerProfile> organizerProfile() {
        return ApiResponse.ok(activityService.organizerProfile(SecurityUtils.currentUser()));
    }

    @GetMapping("/{id}")
    public ApiResponse<ActivityDtos.ActivityView> detail(@PathVariable long id, Authentication authentication) {
        return ApiResponse.ok(activityService.detail(id, optional(authentication)));
    }

    @PostMapping
    public ApiResponse<ActivityDtos.ActivityView> create(@Valid @RequestBody ActivityDtos.CreateActivityRequest request) {
        return ApiResponse.ok(activityService.create(request, SecurityUtils.currentUser()));
    }

    @PostMapping("/{id}/registrations")
    public ApiResponse<ActivityDtos.ActivityView> register(@PathVariable long id) {
        return ApiResponse.ok(activityService.register(id, SecurityUtils.currentUser()));
    }

    @DeleteMapping("/{id}/registrations/me")
    public ApiResponse<ActivityDtos.ActivityView> cancel(@PathVariable long id) {
        return ApiResponse.ok(activityService.cancel(id, SecurityUtils.currentUser()));
    }

    @GetMapping("/{id}/checkin-code")
    public ApiResponse<ActivityDtos.OrganizerCheckinView> checkinCode(@PathVariable long id) {
        return ApiResponse.ok(activityService.organizerCheckin(id, SecurityUtils.currentUser()));
    }

    @PostMapping("/{id}/checkin")
    public ApiResponse<ActivityDtos.CheckinResult> checkin(@PathVariable long id,
                                                           @Valid @RequestBody ActivityDtos.CheckinRequest request) {
        return ApiResponse.ok(activityService.checkin(id, request, SecurityUtils.currentUser()));
    }

    // ---- 组织者 ----

    @GetMapping("/organized")
    public ApiResponse<ActivityDtos.ActivityPage> organized(@RequestParam(required = false) String cursor,
                                                            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(activityService.organized(cursor, limit, SecurityUtils.currentUser()));
    }

    @GetMapping("/{id}/registrations")
    public ApiResponse<ActivityDtos.RosterPage> roster(@PathVariable long id,
                                                       @RequestParam(required = false) String filter,
                                                       @RequestParam(required = false) String cursor,
                                                       @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(activityService.roster(id, filter, cursor, limit, SecurityUtils.currentUser()));
    }

    @PostMapping("/{id}/registrations/{registrationId}/approve")
    public ApiResponse<ActivityDtos.RosterEntry> approve(@PathVariable long id, @PathVariable long registrationId) {
        return ApiResponse.ok(activityService.approve(id, registrationId, SecurityUtils.currentUser()));
    }

    @PostMapping("/{id}/registrations/{registrationId}/reject")
    public ApiResponse<ActivityDtos.RosterEntry> reject(@PathVariable long id, @PathVariable long registrationId) {
        return ApiResponse.ok(activityService.reject(id, registrationId, SecurityUtils.currentUser()));
    }

    @PostMapping("/{id}/registrations/{registrationId}/checkin")
    public ApiResponse<ActivityDtos.RosterEntry> manualCheckin(@PathVariable long id, @PathVariable long registrationId) {
        return ApiResponse.ok(activityService.manualCheckin(id, registrationId, SecurityUtils.currentUser()));
    }

    @PostMapping("/{id}/registrations/{registrationId}/revoke-checkin")
    public ApiResponse<ActivityDtos.RosterEntry> revokeCheckin(@PathVariable long id, @PathVariable long registrationId,
                                                               @Valid @RequestBody ActivityDtos.ReasonRequest request) {
        return ApiResponse.ok(activityService.revokeCheckin(id, registrationId, request, SecurityUtils.currentUser()));
    }

    @PutMapping("/{id}")
    public ApiResponse<ActivityDtos.ActivityView> update(@PathVariable long id,
                                                         @Valid @RequestBody ActivityDtos.UpdateActivityRequest request) {
        return ApiResponse.ok(activityService.update(id, request, SecurityUtils.currentUser()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ActivityDtos.ActivityView> cancelActivity(@PathVariable long id,
                                                                 @Valid @RequestBody ActivityDtos.ReasonRequest request) {
        return ApiResponse.ok(activityService.cancelActivity(id, request, SecurityUtils.currentUser()));
    }

    private static CurrentUser optional(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof CurrentUser user ? user : null;
    }
}
