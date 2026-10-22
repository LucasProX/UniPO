package com.biecuoguo.message.internal;

import com.biecuoguo.message.api.OutboxOperationsDtos;
import com.biecuoguo.security.SecurityUtils;
import com.biecuoguo.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/message-deliveries")
public class OutboxOperationsController {
    private final OutboxOperationsService operations;

    public OutboxOperationsController(OutboxOperationsService operations) { this.operations = operations; }

    @GetMapping
    public ApiResponse<OutboxOperationsDtos.DeliveryPage> list(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(operations.list(state, channel, cursor, limit, SecurityUtils.currentUser()));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<OutboxOperationsDtos.DeliveryView> retry(@PathVariable long id,
                                                               @Valid @RequestBody OutboxOperationsDtos.RetryRequest request) {
        return ApiResponse.ok(operations.retry(id, request, SecurityUtils.currentUser()));
    }
}
