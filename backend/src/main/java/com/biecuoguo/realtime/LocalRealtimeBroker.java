package com.biecuoguo.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 单机广播：publish 直接回调本进程的订阅者。
 *
 * <p>它不是「假实现」——单机部署就是这条路径，和 Redis 版行为一致（只是不跨进程）。
 */
@Component
@ConditionalOnProperty(name = "app.state.backend", havingValue = "memory", matchIfMissing = true)
public class LocalRealtimeBroker implements RealtimeBroker {

    private final AtomicReference<Consumer<BrokerMessage>> consumer = new AtomicReference<>();

    @Override
    public void publish(BrokerMessage message) {
        Consumer<BrokerMessage> target = consumer.get();
        if (target != null) {
            target.accept(message);
        }
    }

    @Override
    public void subscribe(Consumer<BrokerMessage> target) {
        consumer.set(target);
    }
}
