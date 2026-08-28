package com.atest.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

import com.atest.config.AtestProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 附件上传的准入闸：multipart 解析发生在进 Controller 之前（容器边收 body 边写临时文件），
 * 所以并发上限必须在 Filter 层就位 —— 超出水位的上传请求不读 body 直接 429，
 * 突发再大也只有固定数量的 Tomcat 线程会被慢客户端的上传占住，其余 API 不受影响。
 * 水位 = max-concurrent（在写盘） + queue-capacity（排队等写盘）。
 */
@Component
public class UploadGateFilter extends OncePerRequestFilter {

    private static final Pattern UPLOAD_PATH = Pattern.compile("^/api/(tasks|executions)/[^/]+/files/?$");

    private final Semaphore permits;

    public UploadGateFilter(AtestProperties props) {
        int capacity = Math.max(1, props.getAttachments().getMaxConcurrent())
                + Math.max(1, props.getAttachments().getQueueCapacity());
        this.permits = new Semaphore(capacity);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equalsIgnoreCase(request.getMethod())
                && UPLOAD_PATH.matcher(request.getRequestURI()).matches());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!permits.tryAcquire()) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"code\":\"too_many_uploads\",\"message\":\"附件上传并发已满，请稍后重试\","
                    + "\"path\":\"" + request.getRequestURI() + "\",\"status\":429}");
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }

    /** 上传接口是异步的（Controller 返回 CompletableFuture），异步分发阶段不再重复过闸。 */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }
}
