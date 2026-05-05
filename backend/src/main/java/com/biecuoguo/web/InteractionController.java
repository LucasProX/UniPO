package com.biecuoguo.web;

import com.biecuoguo.dto.NoticeDtos;
import com.biecuoguo.dto.ReminderDtos;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.service.InteractionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class InteractionController {
    private final InteractionService interactionService;

    public InteractionController(InteractionService interactionService) {
        this.interactionService = interactionService;
    }

    @GetMapping("/api/favorites")
    public ApiResponse<List<NoticeDtos.NoticeSummary>> favorites() {
        return ApiResponse.ok(interactionService.favorites(SecurityUtils.currentUser()));
    }

    @PostMapping("/api/notices/{id}/favorite")
    public ApiResponse<Boolean> favorite(@PathVariable Long id) {
        interactionService.favorite(id, SecurityUtils.currentUser());
        return ApiResponse.ok(true);
    }

    @DeleteMapping("/api/notices/{id}/favorite")
    public ApiResponse<Boolean> unfavorite(@PathVariable Long id) {
        interactionService.unfavorite(id, SecurityUtils.currentUser());
        return ApiResponse.ok(true);
    }

    @GetMapping("/api/reminders")
    public ApiResponse<List<ReminderDtos.ReminderView>> reminders() {
        return ApiResponse.ok(interactionService.reminders(SecurityUtils.currentUser()));
    }

    @PostMapping("/api/notices/{id}/reminders")
    public ApiResponse<ReminderDtos.ReminderView> createReminder(@PathVariable Long id, @Valid @RequestBody ReminderDtos.ReminderRequest request) {
        return ApiResponse.ok(interactionService.createReminder(id, request, SecurityUtils.currentUser()));
    }

    @PutMapping("/api/reminders/{id}")
    public ApiResponse<ReminderDtos.ReminderView> updateReminder(@PathVariable Long id, @Valid @RequestBody ReminderDtos.ReminderRequest request) {
        return ApiResponse.ok(interactionService.updateReminder(id, request, SecurityUtils.currentUser()));
    }

    @DeleteMapping("/api/reminders/{id}")
    public ApiResponse<Boolean> deleteReminder(@PathVariable Long id) {
        interactionService.deleteReminder(id, SecurityUtils.currentUser());
        return ApiResponse.ok(true);
    }

    @PostMapping("/api/notices/{id}/complete")
    public ApiResponse<Boolean> complete(@PathVariable Long id) {
        interactionService.complete(id, SecurityUtils.currentUser());
        return ApiResponse.ok(true);
    }

    @PostMapping("/api/notices/{id}/ignore")
    public ApiResponse<Boolean> ignore(@PathVariable Long id) {
        interactionService.ignore(id, SecurityUtils.currentUser());
        return ApiResponse.ok(true);
    }
}
