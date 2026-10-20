package com.biecuoguo.realtime;

import java.util.function.Consumer;

/**
 * 跨实例广播。单机用 {@link LocalRealtimeBroker}，多实例用 {@link RedisRealtimeBroker}。
 *
 * <p>注意：broker 只负责「把事件送到各实例」，真正的本地连接投递在 {@link RealtimeHub#deliverLocal}。
 * 这样单机和多实例走同一条代码路径，切换 backend 不需要改业务。
 */
public interface RealtimeBroker {

    record BrokerMessage(Long userId, RealtimeEvent event) {
    }

    void publish(BrokerMessage message);

    void subscribe(Consumer<BrokerMessage> consumer);
}
