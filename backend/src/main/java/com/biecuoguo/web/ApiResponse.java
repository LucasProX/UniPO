package com.biecuoguo.web;

/**
 * 统一成功响应包：{@code {"code":"OK","message":"","data":{},"requestId":"..."}}。
 *
 * <p>为什么不是裸 {@code {"data": ...}}：App 端要在弱网/超时场景下判断「是业务失败还是传输失败」，
 * 以及把用户报的故障对上服务端日志，都需要一个稳定的 {@code code} 和 {@code requestId}。
 * 见 docs/MOBILE-API-PLAN.md 4.2。
 *
 * <p><b>HTTP 状态码保留语义</b>（400/401/403/404/409/413/429/500），
 * 不要退化成「一律 200 + code 判断」——那会让代理层、监控、重试策略全部失效。
 */
public record ApiResponse<T>(String code, String message, T data, String requestId) {

    public static final String CODE_OK = "OK";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(CODE_OK, "", data, RequestContextHolder.requestId());
    }
}