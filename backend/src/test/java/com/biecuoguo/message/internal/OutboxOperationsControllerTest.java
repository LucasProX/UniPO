package com.biecuoguo.message.internal;

import com.biecuoguo.error.StateConflictException;
import com.biecuoguo.message.api.OutboxOperationsDtos;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MVC binding/error semantics only; actual role interception is in the owned MySQL fixture. */
class OutboxOperationsControllerTest {
    private final OutboxOperationsService service = mock(OutboxOperationsService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new OutboxOperationsController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    private final CurrentUser admin = new CurrentUser(7L, "ops", "ADMIN");

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(admin, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void listBindsTheFilterCursorAndPageSize() throws Exception {
        when(service.list("FAILED", "PUSH", "cursor", 2, admin))
                .thenReturn(new OutboxOperationsDtos.DeliveryPage(List.of(metadata("FAILED")), null, false, 2));

        mvc.perform(get("/api/admin/message-deliveries").param("state", "FAILED").param("channel", "PUSH")
                        .param("cursor", "cursor").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items[0].id").value(9))
                .andExpect(jsonPath("$.data.items[0].content").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].claimToken").doesNotExist());

        verify(service).list("FAILED", "PUSH", "cursor", 2, admin);
    }

    @Test
    void retryBindsTheObservedAttemptsAndReason() throws Exception {
        when(service.retry(eq(9L), any(), eq(admin))).thenReturn(metadata("PENDING"));

        mvc.perform(post("/api/admin/message-deliveries/9/retry").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedAttempts\":4,\"reason\":\"配置修复后重试\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("PENDING"));

        verify(service).retry(9L, new OutboxOperationsDtos.RetryRequest(4, "配置修复后重试"), admin);
    }

    @Test
    void invalidRetryBodyIsRejectedBeforeTheService() throws Exception {
        for (String body : List.of("{\"expectedAttempts\":-1,\"reason\":\"fixed\"}",
                "{\"expectedAttempts\":4,\"reason\":\" \"}", "{\"reason\":\"fixed\"}")) {
            mvc.perform(post("/api/admin/message-deliveries/9/retry").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void changedStateUsesStableConflictStatusAndCode() throws Exception {
        when(service.retry(eq(9L), any(), eq(admin))).thenThrow(new StateConflictException("投递状态已变化"));

        mvc.perform(post("/api/admin/message-deliveries/9/retry").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedAttempts\":4,\"reason\":\"fixed\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    private static OutboxOperationsDtos.DeliveryView metadata(String state) {
        OffsetDateTime now = OffsetDateTime.parse("2026-10-22T10:00:00Z");
        return new OutboxOperationsDtos.DeliveryView(9, "0123456789abcdef0123456789abcdef", 5,
                "PUSH", state, 4, "APNS_REJECTED", now, now, null, now);
    }
}
