package com.biecuoguo.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Redis coverage for DP02/R06. Run only through scripts/integration/redis-session.sh.
 * No Spring application context, database, .env import, FLUSHDB, or existing Redis reuse.
 */
@EnabledIfEnvironmentVariable(named = "UNIPO_DP02_REDIS_INTEGRATION", matches = "1")
@Timeout(20)
class RedisSessionStoreIntegrationTest {
    private static final String SESSION_PREFIX = "unipo:session:";
    private static final String USER_PREFIX = "unipo:session:user:";
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RedisSessionStore store;
    private final Set<String> ownedKeys = new HashSet<>();

    @BeforeAll
    static void connectOnlyToOwnedFixture() {
        assertEquals("26379", System.getenv("UNIPO_DP02_REDIS_PORT"),
                "DP02 tests only connect to the dedicated fixture port");
        String runId = System.getenv("UNIPO_DP02_RUN_ID");
        assertNotNull(runId, "Start the fixture with scripts/integration/redis-session.sh");
        assertTrue(runId.matches("[0-9a-f]{16}"), "Invalid fixture run marker");
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration("127.0.0.1", 26379);
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2))
                .shutdownTimeout(Duration.ZERO)
                .build();
        connectionFactory = new LettuceConnectionFactory(server, client);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        assertEquals(runId, redis.opsForValue().get("unipo:dp02:fixture:marker"),
                "Refusing to write to a Redis instance without this run's ownership marker");
        store = new RedisSessionStore(redis, new ObjectMapper());
    }

    @AfterEach
    void deleteOnlyKeysCreatedByThisTest() {
        if (redis != null && !ownedKeys.isEmpty()) {
            redis.delete(ownedKeys);
        }
    }

    @AfterAll
    static void closeClient() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void revokeAllRemovesBothDevicesAndPreservesAnotherUser() {
        long user = uniqueUserId();
        long otherUser = uniqueUserId();
        String first = save(user, Duration.ofSeconds(30));
        String second = save(user, Duration.ofSeconds(30));
        String other = save(otherUser, Duration.ofSeconds(30));

        store.deleteAllForUser(user);

        assertTrue(store.loadAndTouch(first, Duration.ofSeconds(30)).isEmpty());
        assertTrue(store.loadAndTouch(second, Duration.ofSeconds(30)).isEmpty());
        assertTrue(store.loadAndTouch(other, Duration.ofSeconds(30)).isPresent());
    }

    @Test
    void activeSessionRemainsRevocableAcrossTheOriginalIndexLifetime() throws InterruptedException {
        Duration ttl = Duration.ofSeconds(4);
        long user = uniqueUserId();
        String session = save(user, ttl);
        String sessionKey = SESSION_PREFIX + session;
        String userKey = USER_PREFIX + user;
        long originalDeadline = System.nanoTime() + ttl.toNanos();
        assertTrue(Boolean.TRUE.equals(redis.hasKey(userKey)), "Fixture index was not created");

        await(() -> ttlMillis(sessionKey) <= 2_000, Duration.ofSeconds(3));
        assertTrue(store.loadAndTouch(session, ttl).isPresent(), "Active session must resolve before revocation");
        assertTrue(ttlMillis(sessionKey) > 3_000, "Session TTL was not extended");
        await(() -> System.nanoTime() > originalDeadline + Duration.ofMillis(200).toNanos(),
                Duration.ofSeconds(4));

        boolean indexExists = Boolean.TRUE.equals(redis.hasKey(userKey));
        long revocationWindowStarted = System.nanoTime();
        long remainingSessionMillis = ttlMillis(sessionKey);
        assertTrue(remainingSessionMillis > 1_000, "Fixture timing missed the active-session revocation window");
        System.out.printf("R06 before revoke: sessionAlive=true userIndexExists=%s sessionTtlMs=%d%n",
                indexExists, remainingSessionMillis);

        store.deleteAllForUser(user);
        boolean identityStillResolves = store.loadAndTouch(session, ttl).isPresent();
        long revocationElapsedMillis = Duration.ofNanos(System.nanoTime() - revocationWindowStarted).toMillis();
        assertTrue(revocationElapsedMillis < remainingSessionMillis - 200,
                "Fixture timing exceeded the known session lifetime; natural expiry cannot count as revocation");
        System.out.printf("R06 after revoke: oldIdentityResolves=%s%n", identityStillResolves);

        assertFalse(identityStillResolves,
                "R06: revoke-all must reject an active session after the original user-index lifetime; "
                        + "index present before revoke=" + indexExists);
    }

    private String save(long userId, Duration ttl) {
        String id = UUID.randomUUID().toString();
        ownedKeys.add(SESSION_PREFIX + id);
        ownedKeys.add(USER_PREFIX + userId);
        SessionStore.SessionData data = new SessionStore.SessionData(userId, "USER",
                UUID.randomUUID() + "@example.invalid", UUID.randomUUID().toString());
        store.save(id, data, ttl);
        return id;
    }

    private static long ttlMillis(String key) {
        Long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        return ttl == null ? -2 : ttl;
    }

    private static long uniqueUserId() {
        return (UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE) >>> 1;
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "Timed out waiting for the fixture TTL boundary");
            Thread.sleep(20);
        }
    }
}
