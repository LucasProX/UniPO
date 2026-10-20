package com.biecuoguo.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * {@code app.state.backend=redis} 时启动即验证连通性。
 *
 * <p>不验证的话会很难查：进程起得来、接口全 200、只是「退出登录后 token 还能用」——
 * 因为每次 Redis 调用都抛异常，而异常在过滤器里被吞成 401。宁可启动就失败。
 */
@Component
@ConditionalOnProperty(name = "app.state.backend", havingValue = "redis")
public class RedisStateGuard implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(RedisStateGuard.class);

    private final RedisConnectionFactory connectionFactory;

    public RedisStateGuard(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public void afterPropertiesSet() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.ping();
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "app.state.backend=redis，但连不上 Redis（" + ex.getMessage() + "）。"
                            + " 要么把 Redis 起起来，要么设 STATE_BACKEND=memory（多实例部署会失效）。", ex);
        }
        log.info("分布式状态存储已就绪：Redis（撤销名单 / 在线状态 / 限流计数）");
    }
}
