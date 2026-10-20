package com.biecuoguo.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 多实例广播：事件发到 Redis 频道，每个实例订阅后投递给自己的本地连接。
 *
 * <p>为什么不用「粘性会话」：粘性会话只能保证同一客户端回到同一实例，
 * 但 A 实例上的用户给 B 实例上的用户发消息时，事件仍然到不了 B。
 * 发布订阅才是「任意实例发的消息，所有实例都能收到」。
 */
@Component
@ConditionalOnProperty(name = "app.state.backend", havingValue = "redis")
public class RedisRealtimeBroker implements RealtimeBroker, InitializingBean, DisposableBean {

    public static final String CHANNEL = "unipo:realtime";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    private volatile Consumer<BrokerMessage> consumer;

    public RedisRealtimeBroker(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(BrokerMessage message) {
        try {
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(message));
        } catch (Exception e) {
            throw new IllegalStateException("实时事件发布失败", e);
        }
    }

    @Override
    public void subscribe(Consumer<BrokerMessage> target) {
        this.consumer = target;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        RedisConnectionFactory factory = redis.getConnectionFactory();
        if (factory == null) {
            throw new IllegalStateException("Redis 连接工厂为空，无法启动实时广播");
        }
        container.setConnectionFactory(factory);
        container.addMessageListener((message, pattern) -> {
            Consumer<BrokerMessage> target = consumer;
            if (target == null) {
                return;
            }
            try {
                BrokerMessage parsed = objectMapper.readValue(
                        new String(message.getBody(), StandardCharsets.UTF_8), BrokerMessage.class);
                target.accept(parsed);
            } catch (Exception ignored) {
                // 频道里混进坏消息不能让监听线程挂掉
            }
        }, new ChannelTopic(CHANNEL));
        container.afterPropertiesSet();
        container.start();
    }

    @Override
    public void destroy() throws Exception {
        container.destroy();
    }
}
