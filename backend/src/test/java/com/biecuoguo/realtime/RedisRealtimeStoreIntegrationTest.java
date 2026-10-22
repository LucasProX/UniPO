package com.biecuoguo.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.biecuoguo.security.AuthenticationStateService;
import com.biecuoguo.social.api.BlockQueryApi;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real Redis coverage for DP03 replay behavior; run only through the isolated Redis fixture. */
@EnabledIfEnvironmentVariable(named = "UNIPO_DP02_REDIS_INTEGRATION", matches = "1")
@Timeout(30)
class RedisRealtimeStoreIntegrationTest {
    private static final String SEQ_KEY = "unipo:rt:seq";
    private static final String STREAM_PREFIX = "unipo:rt:stream:";
    private static final String MARKER_KEY = "unipo:dp02:fixture:marker";
    private static final Set<String> ownedKeys = new HashSet<>();

    private static LettuceConnectionFactory firstFactory;
    private static LettuceConnectionFactory secondFactory;
    private static StringRedisTemplate redis;
    private static RedisRealtimeStore firstStore;
    private static RedisRealtimeStore secondStore;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void connectOnlyToOwnedFixture() {
        assertEquals("26379", System.getenv("UNIPO_DP02_REDIS_PORT"),
                "DP03 tests only connect to the dedicated fixture port");
        String runId = System.getenv("UNIPO_DP02_RUN_ID");
        assertNotNull(runId, "Start the fixture with scripts/integration/redis-session.sh");
        assertTrue(runId.matches("[0-9a-f]{16}"), "Invalid fixture run marker");

        firstFactory = connectionFactory();
        secondFactory = connectionFactory();
        redis = new StringRedisTemplate(firstFactory);
        StringRedisTemplate secondRedis = new StringRedisTemplate(secondFactory);
        assertEquals(runId, redis.opsForValue().get(MARKER_KEY),
                "Refusing to write to a Redis instance without this run's ownership marker");
        assertEquals(runId, secondRedis.opsForValue().get(MARKER_KEY),
                "Both simulated app nodes must connect to this run's fixture");
        assertNull(redis.opsForValue().get(SEQ_KEY), "Fixture unexpectedly contains an application sequence key");

        objectMapper = new ObjectMapper();
        firstStore = new RedisRealtimeStore(redis, objectMapper);
        secondStore = new RedisRealtimeStore(secondRedis, objectMapper);
        ownedKeys.add(SEQ_KEY);
    }

    @AfterAll
    static void deleteOnlyKeysCreatedByThisTest() {
        try {
            if (redis != null && !ownedKeys.isEmpty()) {
                redis.delete(ownedKeys);
            }
        } finally {
            if (secondFactory != null) { secondFactory.destroy(); }
            if (firstFactory != null) { firstFactory.destroy(); }
        }
    }

    @Test
    void redisReplayUsesApplicationSequenceAndBoundedExclusivePagesWithoutPrivateMessages() throws Exception {
        long userId = UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE;
        String streamKey = STREAM_PREFIX + userId;
        ownedKeys.add(streamKey);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(streamKey)), "Synthetic user stream unexpectedly exists");

        AuthenticationStateService authentication = mock(AuthenticationStateService.class);
        when(authentication.isValid(any())).thenReturn(true);
        var verification = new RealtimeTestSupport.ControlledExecutor();
        var closeExecutor = new RealtimeTestSupport.ControlledExecutor();
        var security = new RealtimeSecurityExecutor(authentication, verification, closeExecutor, Duration.ofSeconds(1));
        try {
            var hub = new RealtimeHub(new LocalRealtimeBroker(), firstStore, objectMapper,
                    new SimpleMeterRegistry(), security, mock(BlockQueryApi.class));

            hub.publish(userId, "message", Map.of("message", Map.of("senderId", 9001L, "content", "private-fixture")));
            assertNull(redis.opsForValue().get(SEQ_KEY), "Private message must not consume a replay sequence");
            assertFalse(Boolean.TRUE.equals(redis.hasKey(streamKey)), "Private message must not create a replay stream");

            long firstSequence = secondStore.nextSeq();
            assertEquals(1L, firstSequence, "The owned Redis fixture should allocate its first global sequence at one");
            firstStore.append(userId, new RealtimeEvent(firstSequence, "profile-update", 1L, Map.of("n", 1)));

            for (int index = 2; index <= 105; index++) {
                hub.publish(userId, "profile-update", Map.of("n", index));
            }
            assertEquals("105", redis.opsForValue().get(SEQ_KEY),
                    "Sequence allocation through the second client and Hub should share one Redis counter");

            List<String> replayFrames = new ArrayList<>();
            var connection = hub.register(RealtimeTestSupport.identity(userId, "redis-fixture"), replayFrames::add, () -> {});
            int replayed = hub.replay(connection, 0L);
            assertEquals(100, replayed, "Hub replay must honor its 100-event upper bound");
            assertEquals(100, replayFrames.size());
            List<Long> replaySequences = new ArrayList<>();
            for (String frame : replayFrames) {
                JsonNode node = objectMapper.readTree(frame);
                assertEquals("profile-update", node.path("event").asText());
                assertFalse(frame.contains("private-fixture"), "Private message content must never enter replay");
                replaySequences.add(node.path("seq").asLong());
            }
            assertEquals(1L, replaySequences.getFirst());
            assertEquals(100L, replaySequences.getLast());
            assertTrue(isStrictlyIncreasing(replaySequences), "Replay frames must be ordered by application sequence");

            long sequence106 = firstStore.nextSeq();
            long sequence107 = secondStore.nextSeq();
            assertEquals(106L, sequence106);
            assertEquals(107L, sequence107);
            firstStore.append(userId, new RealtimeEvent(sequence107, "profile-update", 107L, Map.of("n", 107)));
            secondStore.append(userId, new RealtimeEvent(sequence106, "profile-update", 106L, Map.of("n", 106)));

            List<RealtimeEvent> firstPage = firstStore.after(userId, 104L, 2);
            assertEquals(List.of(105L, 106L), firstPage.stream().map(RealtimeEvent::seq).toList(),
                    "Pages must use exclusive application-sequence cursors, not Redis Stream IDs");
            List<RealtimeEvent> secondPage = secondStore.after(userId, firstPage.getLast().seq(), 2);
            assertEquals(List.of(107L), secondPage.stream().map(RealtimeEvent::seq).toList());
            assertTrue(firstStore.after(userId, 104L, 1).size() <= 1, "Store page size must honor the requested limit");
            assertTrue(firstStore.after(userId, 107L, 2).isEmpty(), "The cursor is exclusive at the end of the stream");
        } finally {
            security.destroy();
        }
    }

    private static LettuceConnectionFactory connectionFactory() {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration("127.0.0.1", 26379);
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2))
                .shutdownTimeout(Duration.ZERO)
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(server, client);
        factory.afterPropertiesSet();
        return factory;
    }

    private static boolean isStrictlyIncreasing(List<Long> values) {
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index - 1) >= values.get(index)) { return false; }
        }
        return true;
    }
}
