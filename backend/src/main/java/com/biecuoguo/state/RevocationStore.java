package com.biecuoguo.state;

import java.time.Instant;

/**
 * 已撤销 token 的存放处。
 *
 * <p>两种实现：{@code memory}（进程内，单实例）与 {@code redis}（多实例共享），
 * 由 {@code app.state.backend} 选择。选错不会报错，但多实例部署时会出现
 * 「A 实例退出登录了，B 实例还认这个 token」——这正是 ISSUES 里的 Q-4。
 */
public interface RevocationStore {
    /** 拉黑一个 token，直到它自己过期。 */
    void revoke(String token, Instant expiresAt);

    /** 是否已被拉黑。实现抛异常时调用方按「已撤销」处理（宁可多踢一次，也不要放过）。 */
    boolean isRevoked(String token);
}
