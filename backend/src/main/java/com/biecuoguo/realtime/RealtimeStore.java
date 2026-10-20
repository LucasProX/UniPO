package com.biecuoguo.realtime;

import java.util.List;

/**
 * 实时事件的短期留存，只用于断线续传（不是消息历史）。
 *
 * <p>保留窗口刻意很小（几百条/用户）：它解决的是「网络抖一下、App 切后台回来」，
 * 不是「离线三天后补消息」——后者靠未读数与拉取接口。
 */
public interface RealtimeStore {

    /** 分配下一个全局序号。多实例下必须来自共享存储。 */
    long nextSeq();

    void append(Long userId, RealtimeEvent event);

    /** 取 {@code afterSeq} 之后的事件，最多 {@code limit} 条，按 seq 升序。 */
    List<RealtimeEvent> after(Long userId, long afterSeq, int limit);
}
