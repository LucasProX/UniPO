package com.biecuoguo.state;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 单实例实现：进程内 Map。重启即清空（等于所有人重新登录一次的能力都没有，只是不再拉黑）。 */
@Component
@ConditionalOnProperty(name = "app.state.backend", havingValue = "memory", matchIfMissing = true)
public class MemoryRevocationStore implements RevocationStore {
    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();

    @Override
    public void revoke(String token, Instant expiresAt) {
        cleanup();
        revoked.put(token, expiresAt);
    }

    @Override
    public boolean isRevoked(String token) {
        cleanup();
        Instant expiresAt = revoked.get(token);
        return expiresAt != null && expiresAt.isAfter(Instant.now());
    }

    private void cleanup() {
        Instant now = Instant.now();
        revoked.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }
}
