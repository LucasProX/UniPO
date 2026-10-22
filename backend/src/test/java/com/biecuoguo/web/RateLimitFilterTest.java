package com.biecuoguo.web;

import com.biecuoguo.service.RateLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {
    @Test
    void retryIdsShareOneBoundedBucketAndBlockedRequestsHaveTheStandardEnvelope() throws Exception {
        var service = mock(RateLimitService.class);
        when(service.acquire(eq("outbox-retry|127.0.0.1"), anyInt(), any()))
                .thenReturn(new RateLimitService.Result(false, 17));
        var filter = filter(service);
        for (String id : new String[]{"12", "99"}) {
            var response = new MockHttpServletResponse();
            AtomicInteger passed = new AtomicInteger();
            filter.doFilter(request("POST", "/api/admin/message-deliveries/" + id + "/retry"), response,
                    (req, res) -> passed.incrementAndGet());
            assertEquals(429, response.getStatus());
            assertEquals("17", response.getHeader("Retry-After"));
            assertEquals("RATE_LIMITED", new ObjectMapper().readTree(response.getContentAsString()).path("code").asText());
            assertEquals(0, passed.get());
        }
        verify(service, org.mockito.Mockito.times(2))
                .acquire("outbox-retry|127.0.0.1", 20, Duration.ofMinutes(1));
    }

    @Test
    void messageIdsShareOneWriteBucketWithoutLimitingReadPages() throws Exception {
        var service = mock(RateLimitService.class);
        when(service.acquire(any(), anyInt(), any())).thenReturn(new RateLimitService.Result(true, 0));
        var filter = filter(service);
        filter.doFilter(request("POST", "/api/messages/conversations/7/messages"), new MockHttpServletResponse(), (req, res) -> {});
        verify(service).acquire("message-send|127.0.0.1", 120, Duration.ofMinutes(1));

        var readService = mock(RateLimitService.class);
        filter(readService).doFilter(request("GET", "/api/messages/conversations/7/messages/page"),
                new MockHttpServletResponse(), (req, res) -> {});
        verifyNoInteractions(readService);
    }

    @Test
    void dynamicRetryRuleCannotAccidentallyMatchUnrelatedNestedRoutes() throws Exception {
        var service = mock(RateLimitService.class);
        AtomicInteger passed = new AtomicInteger();
        filter(service).doFilter(request("POST", "/api/admin/message-deliveries/1/extra/retry"),
                new MockHttpServletResponse(), (req, res) -> passed.incrementAndGet());
        assertEquals(1, passed.get());
        verifyNoInteractions(service);
    }

    private static RateLimitFilter filter(RateLimitService service) {
        return new RateLimitFilter(service, new ObjectMapper().findAndRegisterModules(), true, false,
                new SimpleMeterRegistry());
    }

    private static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
