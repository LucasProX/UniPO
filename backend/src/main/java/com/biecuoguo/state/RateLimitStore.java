package com.biecuoguo.state;

import java.time.Duration;

/** 限流计数器存放处。{@code key} 已经带上窗口编号，所以实现只管「加一并返回」。 */
public interface RateLimitStore {
    /**
     * 计数加一并返回当前值（1 表示这个窗口里的第一次）。
     *
     * @param window 只在实现需要设置过期时间时使用；key 里已经含窗口编号
     */
    long increment(String key, Duration window);
}
