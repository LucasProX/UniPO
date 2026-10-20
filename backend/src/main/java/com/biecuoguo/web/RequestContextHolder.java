package com.biecuoguo.web;

import java.util.UUID;

/**
 * 请求 id 传递。
 *
 * <p>requestId 的作用：用户截图一个报错，我们拿这个 id 就能在日志里捞出那一次请求的
 * 完整链路（哪个接口、哪个用户、哪条 SQL 慢）。
 *
 * <p>入站时 {@link RequestIdFilter} 会读 {@code X-Request-Id} 请求头（上游网关/LB 可能已带），
 * 没有就自己生成一个，并回写到响应头。
 */
public final class RequestContextHolder {

    public static final String HEADER = "X-Request-Id";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private RequestContextHolder() {
    }

    static void set(String requestId) {
        CURRENT.set(requestId);
    }

    static void clear() {
        CURRENT.remove();
    }

    /** 当前请求 id；不在请求线程里调用时返回一次性 id（不会污染别的请求）。 */
    public static String requestId() {
        String id = CURRENT.get();
        return id != null ? id : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}