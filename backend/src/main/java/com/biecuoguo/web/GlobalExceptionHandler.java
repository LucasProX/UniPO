package com.biecuoguo.web;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(ex.getMessage()));
    }

    @ExceptionHandler({NoSuchElementException.class})
    ResponseEntity<ErrorResponse> handleNotFound(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(ex.getMessage()));
    }

    @ExceptionHandler({BadCredentialsException.class})
    ResponseEntity<ErrorResponse> handleBadCredentials(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of("邮箱或密码不正确"));
    }

    @ExceptionHandler({AuthenticationException.class})
    ResponseEntity<ErrorResponse> handleAuthentication(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of("请先登录"));
    }

    @ExceptionHandler({AccessDeniedException.class})
    ResponseEntity<ErrorResponse> handleAccessDenied(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of("没有权限执行该操作"));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ErrorResponse> handleValidation(Exception ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("请求参数不完整或格式不正确"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ErrorResponse.of("图片太大了，请换一张 20MB 以内的图片"));
    }
}
