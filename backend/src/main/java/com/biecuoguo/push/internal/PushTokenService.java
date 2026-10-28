package com.biecuoguo.push.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.biecuoguo.auth.api.AccountSecurityApi;
import com.biecuoguo.auth.api.DeviceSessionsEnded;
import com.biecuoguo.domain.DeviceToken;
import com.biecuoguo.mapper.DeviceTokenMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class PushTokenService {
    private static final Logger log = LoggerFactory.getLogger(PushTokenService.class);

    /** 每轮清理最多看这么多个会话，免得一次查询太大。 */
    static final int CLEANUP_BATCH = 500;

    private final DeviceTokenMapper mapper;
    private final AccountSecurityApi sessions;

    public PushTokenService(DeviceTokenMapper mapper, AccountSecurityApi sessions) {
        this.mapper = mapper;
        this.sessions = sessions;
    }

    /**
     * 同一个设备 token 只保留一条：换账号登录时先删再插，归属跟着最后登录的人走。
     * 记下当前设备会话，退出这台设备或会话失效时这一行会被删掉（M05）。
     */
    @Transactional
    public void register(Long userId, String sessionId, String token, String platform, String environment) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token 不能为空");
        }
        mapper.delete(new LambdaQueryWrapper<DeviceToken>().eq(DeviceToken::getToken, token));
        DeviceToken entity = new DeviceToken();
        entity.setUserId(userId);
        entity.setDeviceSessionId(sessionId);
        entity.setToken(token);
        entity.setPlatform(platform == null || platform.isBlank() ? "ios" : platform);
        entity.setEnvironment(environment == null || environment.isBlank() ? "sandbox" : environment);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setLastSeenAt(LocalDateTime.now());
        mapper.insert(entity);
    }

    /** 不带会话的登记只给投递账本的隔离测试用；这种行会在下一轮清理时删掉。 */
    @Transactional
    public void register(Long userId, String token, String platform, String environment) {
        register(userId, null, token, platform, environment);
    }

    @Transactional
    public void unregister(String token) {
        mapper.delete(new LambdaQueryWrapper<DeviceToken>().eq(DeviceToken::getToken, token));
    }

    @Transactional
    public void deleteAllForUser(Long userId) {
        mapper.delete(new LambdaQueryWrapper<DeviceToken>().eq(DeviceToken::getUserId, userId));
    }

    /**
     * 退出这台设备、全部退出、改密 / 封禁 / 注销推进身份版本时，随撤销事务一起删掉对应设备，
     * 旧手机不会再收到这个账号的推送。已经交给 APNs 的那一条撤不回来。
     */
    @EventListener
    public void onSessionsEnded(DeviceSessionsEnded event) {
        LambdaQueryWrapper<DeviceToken> rows = new LambdaQueryWrapper<DeviceToken>().eq(DeviceToken::getUserId, event.userId());
        if (!event.allSessions()) {
            rows.eq(DeviceToken::getDeviceSessionId, event.sessionId());
        }
        mapper.delete(rows);
    }

    /**
     * 会话自然过期不会触发撤销，所以每小时对一次账：会话已经无效的设备删掉，没有会话的老登记也删掉。
     * App 每次以登录状态启动都会重新登记，误删的代价只是下次打开前收不到推送。
     */
    @Scheduled(initialDelay = 5 * 60 * 1000, fixedDelay = 60 * 60 * 1000)
    public void pruneEndedSessions() {
        try {
            int removed = mapper.delete(new LambdaQueryWrapper<DeviceToken>().isNull(DeviceToken::getDeviceSessionId));
            String after = "";
            while (true) {
                List<String> bound = mapper.selectList(new LambdaQueryWrapper<DeviceToken>()
                                .select(DeviceToken::getDeviceSessionId)
                                .gt(DeviceToken::getDeviceSessionId, after)
                                .groupBy(DeviceToken::getDeviceSessionId)
                                .orderByAsc(DeviceToken::getDeviceSessionId)
                                .last("LIMIT " + CLEANUP_BATCH))
                        .stream().map(DeviceToken::getDeviceSessionId).filter(Objects::nonNull).toList();
                if (bound.isEmpty()) break;
                Set<String> active = sessions.activeSessions(bound);
                List<String> ended = bound.stream().filter(id -> !active.contains(id)).toList();
                if (!ended.isEmpty()) {
                    removed += mapper.delete(new LambdaQueryWrapper<DeviceToken>().in(DeviceToken::getDeviceSessionId, ended));
                }
                if (bound.size() < CLEANUP_BATCH) break;
                after = bound.get(bound.size() - 1);
            }
            if (removed > 0) log.info("Pruned {} push registrations whose sessions ended", removed);
        } catch (RuntimeException unavailable) {
            log.warn("Push registration cleanup failed code=PUSH_CLEANUP_UNAVAILABLE");
        }
    }

    public List<String> tokensForUser(Long userId) {
        return mapper.selectList(new LambdaQueryWrapper<DeviceToken>().eq(DeviceToken::getUserId, userId))
                .stream().map(DeviceToken::getToken).toList();
    }
}
