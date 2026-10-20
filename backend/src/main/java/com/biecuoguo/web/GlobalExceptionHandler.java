package com.biecuoguo.web;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.NoSuchElementException;

/**
 * 全局异常 -> 统一错误包 {@link ErrorResponse}。
 *
 * <p>两个原则：
 * <ol>
 *   <li><b>HTTP 状态码保留语义</b>：400 参数、401 未登录、403 无权限、404 不存在、
 *       413 太大、429 限流、500 服务端 bug。不要一律 200。</li>
 *   <li><b>业务码（{@link ErrorCode}）与 HTTP 码各司其职</b>：前者给 App 做分支，
 *       后者给代理/监控/重试策略看。</li>
 * </ol>
 *
 * <p>500 兜底里**不能**把异常的 message 吐给客户端——可能含 SQL、路径、内部主机名。
 * 只回一个通用文案 + requestId，详细信息打日志。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---------- 400 参数类 ----------

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return badRequest(ErrorCode.VALIDATION_FAILED, ex.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ErrorResponse> handleValidation(Exception ex) {
        return badRequest(ErrorCode.VALIDATION_FAILED, null);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> handleBadParameter(Exception ex) {
        return badRequest(ErrorCode.VALIDATION_FAILED, "请求参数不完整或格式不正确");
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ErrorResponse> handleDuplicateKey(DuplicateKeyException ex) {
        return badRequest(ErrorCode.DUPLICATE, null);
    }

    /** 请求体不是合法 JSON，或字段类型不对（如时间只给了裸 LocalDateTime、没带偏移）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return badRequest(ErrorCode.VALIDATION_FAILED, "请求体格式不正确");
    }

    // ---------- 401 / 403 ----------

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        return build(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_INVALID_CREDENTIALS, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
        return build(HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_UNAUTHENTICATED, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return build(HttpStatus.FORBIDDEN, ErrorCode.AUTH_FORBIDDEN, null);
    }

    // ---------- 404 ----------

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException ex) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "接口不存在");
    }

    // ---------- 405 / 413 ----------

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.VALIDATION_FAILED, "不支持的请求方法");
    }

    @ExceptionHandler({MaxUploadSizeExceededException.class, MultipartException.class})
    ResponseEntity<ErrorResponse> handleMaxUploadSize(Exception ex) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.MEDIA_TOO_LARGE, null);
    }

    // ---------- 500 兜底 ----------

    /**
     * 兜底所有没被上面接住的异常。
     *
     * <p>没有这个 handler 时，未知异常走 Spring 默认错误页（一坨 HTML 或
     * Boot 的 {"timestamp","status","error","path"}），App 端解析不出 code，
     * 也拿不到 requestId。
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("未处理异常 requestId={}", RequestContextHolder.requestId(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL, null);
    }

    // ---------- helpers ----------

    private static ResponseEntity<ErrorResponse> badRequest(ErrorCode code, String message) {
        return build(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ResponseEntity<ErrorResponse> build(HttpStatus status, ErrorCode code, String message) {
        ErrorResponse body = (message == null || message.isBlank())
                ? ErrorResponse.of(code)
                : ErrorResponse.of(code, message);
        return ResponseEntity.status(status).body(body);
    }
}