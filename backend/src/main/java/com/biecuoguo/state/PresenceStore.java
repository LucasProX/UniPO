package com.biecuoguo.state;

import java.time.Duration;

/** 在线状态存放处：记「某人最后一次心跳」，超过 {@link #ONLINE_WINDOW} 就算离线。 */
public interface PresenceStore {
    /** 心跳超过这个时长就算离线。前端心跳间隔是 10s，留 2.5 倍余量。 */
    Duration ONLINE_WINDOW = Duration.ofSeconds(25);

    void touch(Long userId);

    void clear(Long userId);

    boolean isOnline(Long userId);
}
