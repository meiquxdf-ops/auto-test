package com.hjmicro.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class UploadExecutorConfig {

    @Bean(name = "uploadExecutor")
    public ThreadPoolTaskExecutor uploadExecutor(
            @Value("${upload.executor.coreSize:4}") int coreSize,
            @Value("${upload.executor.maxSize:8}") int maxSize,
            @Value("${upload.executor.queueCapacity:100}") int queueCapacity,
            @Value("${upload.executor.keepAliveSeconds:60}") int keepAliveSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix("upload-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
