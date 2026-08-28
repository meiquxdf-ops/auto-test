package com.atest.common;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> rsp = build(e.getStatus(), e.getCode(), e.getMessage(), req);
        rsp.getBody().putAll(e.getExtra());
        return rsp;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e,
                                                                HttpServletRequest req) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "bad_request", msg.isEmpty() ? e.getMessage() : msg, req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegal(IllegalArgumentException e, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "bad_request", e.getMessage(), req);
    }

    /** A client closing an SSE stream is normal; there is no usable response left to write into. */
    @ExceptionHandler({AsyncRequestNotUsableException.class, IOException.class})
    public ResponseEntity<Void> handleClientAbort(Exception e, HttpServletRequest req) {
        log.debug("client aborted {} {}: {}", req.getMethod(), req.getRequestURI(), e.toString());
        return ResponseEntity.ok().build();
    }

    /** 附件超过 32MB：容器在解析 multipart 时边收边计数、超限即中止，这里翻译成 413。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadTooLarge(MaxUploadSizeExceededException e,
                                                                    HttpServletRequest req) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large", "文件超过单附件上限（32MB）", req);
    }

    /** 缺 file 字段 / multipart 体损坏都是调用方问题，不是 500。 */
    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<Map<String, Object>> handleBadMultipart(Exception e, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "bad_request", e.getMessage(), req);
    }

    /** 异步接口（CompletableFuture）里抛出的业务异常可能包一层 CompletionException，剥掉再走既有映射。 */
    @ExceptionHandler(CompletionException.class)
    public ResponseEntity<Map<String, Object>> handleCompletion(CompletionException e, HttpServletRequest req) {
        Throwable cause = e.getCause();
        if (cause instanceof ApiException api) {
            return handleApi(api, req);
        }
        log.error("unhandled async error on {} {}", req.getMethod(), req.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                String.valueOf((cause == null ? e : cause).getMessage()), req);
    }

    /** An unmatched path is a routine 404; without this the catch-all turns it into a 500. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e,
                                                                HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "not_found", "no such path", req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception e, HttpServletRequest req) {
        log.error("unhandled error on {} {}", req.getMethod(), req.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", String.valueOf(e.getMessage()), req);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String code, String message,
                                                      HttpServletRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("path", req.getRequestURI());
        body.put("status", status.value());
        return ResponseEntity.status(status).body(body);
    }
}
