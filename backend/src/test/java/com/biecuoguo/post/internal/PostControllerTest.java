package com.biecuoguo.post.internal;

import com.biecuoguo.post.api.PostDtos;
import com.biecuoguo.security.CurrentUser;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PostControllerTest {
    private final PostService postService = mock(PostService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PostController(postService)).build();
    private final CurrentUser currentUser = new CurrentUser(17L, "student", "USER");

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void commentBindsAndForwardsIdempotencyKey() throws Exception {
        authenticate();
        when(postService.comment(eq(9L), any(), eq(currentUser), eq("comment-try-1"))).thenReturn(
                new PostDtos.CommentView(33L, 9L, 17L, null, "hello", 0, 0,
                        false, false, "visible", OffsetDateTime.parse("2026-10-22T12:00:00+08:00"), null));

        mvc.perform(post("/api/posts/9/comments")
                        .header("Idempotency-Key", "comment-try-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value(33));

        verify(postService).comment(eq(9L), any(PostDtos.CreateCommentRequest.class), eq(currentUser), eq("comment-try-1"));
    }

    @Test
    void shareBindsAndForwardsIdempotencyKey() throws Exception {
        authenticate();
        when(postService.share(9L, currentUser, "share-try-1"))
                .thenReturn(new PostDtos.ShareResponse("/posts/9", 4));

        mvc.perform(post("/api/posts/9/share").header("Idempotency-Key", "share-try-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shareCount").value(4));

        verify(postService).share(9L, currentUser, "share-try-1");
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(currentUser, null, List.of()));
    }
}
