package com.biecuoguo.push.api;

import java.util.Map;

/** 跨模块的推送入口；具体 APNs/日志实现留在 push.internal。 */
public interface PushService {
    /**
     * Completes the provider attempt before returning. A normal return does not assert device delivery.
     * Durable callers supply a stable 32-hex {@code eventId} in data and handle
     * {@link PushDeliveryException} without logging notification content or device credentials.
     */
    void send(long userId, String title, String body, Map<String, String> data);
}
