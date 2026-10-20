package com.biecuoguo.push.internal;

import com.biecuoguo.push.api.PushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;

/** 本地/未配置 APNs 时的默认实现：只打日志，方便联调。 */
@Service
@ConditionalOnProperty(prefix = "app.push.apns", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LoggingPushService implements PushService {
    private static final Logger log = LoggerFactory.getLogger(LoggingPushService.class);

    @Override
    public void send(long userId, String title, String body, Map<String, String> data) {
        log.info("push(mock) user={} title={} body={} data={}", userId, title, body, data);
    }
}
