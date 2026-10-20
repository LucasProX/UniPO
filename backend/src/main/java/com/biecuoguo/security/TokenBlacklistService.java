package com.biecuoguo.security;

import com.biecuoguo.state.RevocationStore;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 退出登录后的 token 拉黑。
 *
 * <p>真正存哪由 {@link RevocationStore} 决定（{@code app.state.backend=memory|redis}）：
 * 单实例用进程内 Map，多实例必须换成 Redis，否则「在 A 实例退出登录」不会影响 B 实例——
 * 也就是 ISSUES 里的 Q-4。
 */
@Service
public class TokenBlacklistService {
    private final RevocationStore store;

    public TokenBlacklistService(RevocationStore store) {
        this.store = store;
    }

    public void revoke(String token, Instant expiresAt) {
        if (token == null || token.isBlank() || expiresAt == null) {
            return;
        }
        store.revoke(token, expiresAt);
    }

    public boolean isRevoked(String token) {
        return token != null && !token.isBlank() && store.isRevoked(token);
    }
}
