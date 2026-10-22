package com.biecuoguo.state;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Duration;
import java.util.Optional;

/**
 * Web 登录会话的存储。
 *
 * <p>为什么需要它：Web 端改用 httpOnly Cookie 之后，Cookie 里只放一个不透明的 session id，
 * 真正的登录态（userId / role / csrfToken）必须放在服务端。放 Redis 才能做到
 * 「改密即失效」「踢掉所有设备」「多实例共享」——纯 JWT 做不到即时吊销。
 *
 * <p>这里保存 Cookie 外壳；Cookie 和 App Bearer 还必须通过同一持久设备/身份版本校验，
 * 见 AccountSecurityApi。Cookie 缓存不再是撤销的唯一权威。
 *
 * <p>实现由 {@code app.state.backend} 选择（memory / redis），和另外三个 store 一致。
 */
public interface SessionStore {

    /** 一个登录会话的最小信息。csrfToken 用于双提交校验。 */
    record SessionData(@JsonFormat(shape = JsonFormat.Shape.STRING) long userId, String role, String email, String csrfToken, Long authVersion, String deviceSessionId) {
        public SessionData(long userId, String role, String email, String csrfToken) {
            this(userId, role, email, csrfToken, null, null);
        }
    }

    void save(String sessionId, SessionData data, Duration ttl);

    /** 读取并续期（滑动过期）。不存在或已过期返回 empty。 */
    Optional<SessionData> loadAndTouch(String sessionId, Duration ttl);

    /** Read without renewal, used by realtime identity validation. */
    Optional<SessionData> load(String sessionId);

    void delete(String sessionId, long userId);

    /** 改密 / 封号 / 用户主动「退出所有设备」时用。 */
    void deleteAllForUser(long userId);
}
