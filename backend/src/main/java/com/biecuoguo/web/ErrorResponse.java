package com.biecuoguo.web;

import java.time.Instant;

/**
 * 统一错误响应包：{@code {"code":"AUTH_INVALID_CREDENTIALS","message":"...","data":null,"requestId":"..."}}。
 *
 * <p>{@code code} 是稳定契约（见 {@link ErrorCode}），App 端 switch 它；
 * {@code message} 是给人看的中文提示，<b>不要拿它做逻辑判断</b>。
 * {@code timestamp} 保留是因为前端历史代码在读它；新代码请用 requestId 关联日志。
 */
public record ErrorResponse(String code, String message, Object data, String requestId, Instant timestamp) {

    public static ErrorResponse of(ErrorCode code) {
        return new ErrorResponse(code.name(), code.defaultMessage(), null,
                RequestContextHolder.requestId(), Instant.now());
    }

    /** 覆盖默认中文提示。仅当调用点能给出更具体的信息时使用。 */
    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.name(), message, null,
                RequestContextHolder.requestId(), Instant.now());
    }

    /** 附带 data 的错误（如校验失败的字段列表）。 */
    public static ErrorResponse of(ErrorCode code, String message, Object data) {
        return new ErrorResponse(code.name(), message, data,
                RequestContextHolder.requestId(), Instant.now());
    }
}