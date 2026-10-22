package com.biecuoguo.message.internal;

import com.biecuoguo.error.StateConflictException;
import com.biecuoguo.message.api.MessageDtos;
import com.biecuoguo.security.CurrentUser;
import com.biecuoguo.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
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

class MessageControllerTest {
    private final MessageService service = mock(MessageService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MessageController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    private final CurrentUser viewer = new CurrentUser(17L, "fixture", "USER");

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void conversationAndHistoryPagesBindTheirCursorAndLimitWithoutCollidingWithTheIdRoute() throws Exception {
        authenticate();
        when(service.conversationPage("position", 7, viewer))
                .thenReturn(new MessageDtos.ConversationPage(List.of(), null, false, 7));
        when(service.messagePage(5L, "older", null, 2, viewer))
                .thenReturn(new MessageDtos.MessagePage(List.of(), null, false, 2));

        mvc.perform(get("/api/messages/conversations/page").param("cursor", "position").param("limit", "7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.limit").value(7));
        mvc.perform(get("/api/messages/conversations/5/messages/page").param("cursor", "older").param("limit", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.limit").value(2));
        verify(service).conversationPage("position", 7, viewer);
        verify(service).messagePage(5L, "older", null, 2, viewer);
    }

    @Test
    void incrementalRecoveryBindsTheLastKnownMessageId() throws Exception {
        authenticate();
        when(service.messagePage(5L, null, 99L, 20, viewer))
                .thenReturn(new MessageDtos.MessagePage(List.of(), null, false, 20));
        mvc.perform(get("/api/messages/conversations/5/messages/page").param("afterId", "99").param("limit", "20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.limit").value(20));
        verify(service).messagePage(5L, null, 99L, 20, viewer);
    }

    @Test
    void sendBindsTheClientIntentAndReturnsItAsAnAdditiveResponseField() throws Exception {
        authenticate();
        when(service.send(eq(5L), any(), eq(viewer))).thenReturn(new MessageDtos.MessageView(
                99L, 5L, 17L, 20L, "hello", false, OffsetDateTime.parse("2026-10-22T19:00:00+08:00"), "client-intent"));

        mvc.perform(post("/api/messages/conversations/5/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\",\"clientMessageId\":\"client-intent\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.clientMessageId").value("client-intent"));
        verify(service).send(5L, new MessageDtos.SendMessageRequest("hello", "client-intent"), viewer);
    }

    @Test
    void legacyBodyWithoutIntentRemainsValid() throws Exception {
        authenticate();
        mvc.perform(post("/api/messages/conversations/5/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"legacy\"}"))
                .andExpect(status().isOk());
        verify(service).send(5L, new MessageDtos.SendMessageRequest("legacy"), viewer);
    }

    @Test
    void malformedClientIntentKeysFailValidationBeforeTheService() throws Exception {
        authenticate();
        for (String key : List.of("", "not a key", "非ASCII", "a".repeat(65))) {
            mvc.perform(post("/api/messages/conversations/5/messages").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"content\":\"hello\",\"clientMessageId\":\"" + key + "\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void changedIntentIsAStableConflictResponse() throws Exception {
        authenticate();
        when(service.send(eq(5L), any(), eq(viewer))).thenThrow(new StateConflictException("消息重试标识已用于另一条消息"));
        mvc.perform(post("/api/messages/conversations/5/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\",\"clientMessageId\":\"client-intent\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(viewer, null, List.of()));
    }
}
