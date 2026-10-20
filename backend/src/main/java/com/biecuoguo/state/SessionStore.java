package com.biecuoguo.state;

import java.time.Duration;
import java.util.Optional;

/**
 * Web 登录会话的存储。
 *
 * <p>为什么需要它：Web 端改用 httpOnly Cookie 之后，Cookie 里只放一个不透明的 session id，
 * 真正的登录态（userId / role / csrfToken）必须放在服务端。放 Redis 才能做到
 * 「改密即失效」「踢掉所有设备」「多实例共享」——纯 JWT 做不到即时吊销。
 *
 * <p>App 端不用这套：App 走 Bearer token + Keychain，见 {@code JwtService}。
 * 两套载体，共用同一个用户身份模型。
 *
 * <p>实现由 {@code app.state.backend} 选择（memory / redis），和另外三个 store 一致。
 */
public interface SessionStore {

    /** 一个登录会话的最小信息。csrfToken 用于双提交校验。 */
    record SessionData(long userId, String role, String email, String csrfToken) {
    }

    void save(String sessionId, SessionData data, Duration ttl);

    /** 读取并续期（滑动过期）。不存在或已过期返回 empty。 */
    Optional<SessionData> loadAndTouch(String sessionId, Duration ttl);

    void delete(String sessionId, long userId);

    /** 改密 / 封号 / 用户主动「退出所有设备」时用。 */
    void deleteAllForUser(long userId);
}
