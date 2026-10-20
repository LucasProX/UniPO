package com.biecuoguo.push.api;

import java.util.Map;

/** 跨模块的推送入口；具体 APNs/日志实现留在 push.internal。 */
public interface PushService {
    void send(long userId, String title, String body, Map<String, String> data);
}
