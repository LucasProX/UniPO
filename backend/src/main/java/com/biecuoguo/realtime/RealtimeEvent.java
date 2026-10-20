package com.biecuoguo.realtime;

/**
 * 实时事件。{@code seq} 是全局单调递增序号，客户端断线重连时带 {@code lastEventId} 续传。
 *
 * <p>为什么不用时间戳当游标：同一毫秒内可能有多条事件，时间戳会漏。
 */
public record RealtimeEvent(long seq, String event, long ts, Object payload) {
}
