package com.biecuoguo.realtime;

/**
 * 实时事件。可重放事件的 {@code seq} 是全局单调递增序号，客户端断线重连时带 {@code lastEventId} 续传。
 * 私信只做在线投递，序号为 0 且不参与通用续传游标。
 *
 * <p>为什么不用时间戳当游标：同一毫秒内可能有多条事件，时间戳会漏。
 */
public record RealtimeEvent(long seq, String event, long ts, Object payload) {
}
