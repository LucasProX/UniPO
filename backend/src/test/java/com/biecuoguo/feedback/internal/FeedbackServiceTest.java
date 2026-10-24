package com.biecuoguo.feedback.internal;

import com.biecuoguo.feedback.api.FeedbackDtos;
import com.biecuoguo.mapper.UserMapper;
import com.biecuoguo.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FeedbackServiceTest {
    private final FeedbackMapper mapper = mock(FeedbackMapper.class);
    private final FeedbackService service = new FeedbackService(mapper, mock(UserMapper.class));
    private final CurrentUser user = new CurrentUser(7L, "feedback@unipo.invalid", "USER", 1L, "session");

    @Test
    void storesTrimmedFeedbackForTheSignedInUser() {
        doAnswer(call -> { ((Feedback) call.getArgument(0)).setId(31L); return 1; }).when(mapper).insert(any(Feedback.class));
        assertEquals(31L, service.submit(new FeedbackDtos.CreateFeedbackRequest("bug", "  消息发不出去了  ", "1.0 (12)", " iPhone 17 "), user));
        ArgumentCaptor<Feedback> saved = ArgumentCaptor.forClass(Feedback.class);
        verify(mapper).insert(saved.capture());
        assertEquals(7L, saved.getValue().getUserId());
        assertEquals("消息发不出去了", saved.getValue().getContent());
        assertEquals("iPhone 17", saved.getValue().getDevice());
        assertEquals("new", saved.getValue().getStatus());
    }

    @Test
    void rejectsGuestsUnknownCategoriesAndShortContent() {
        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> service.submit(new FeedbackDtos.CreateFeedbackRequest("bug", "这里有问题啊", null, null), null));
        assertThrows(IllegalArgumentException.class,
                () -> service.submit(new FeedbackDtos.CreateFeedbackRequest("praise", "这里有问题啊", null, null), user));
        assertThrows(IllegalArgumentException.class,
                () -> service.submit(new FeedbackDtos.CreateFeedbackRequest("bug", "  四个字  ", null, null), user));
        verify(mapper, never()).insert(any(Feedback.class));
    }
}
