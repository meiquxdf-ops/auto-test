package com.hjmicro.service;


import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 负责sse的一些事
 */
public interface SseService {

    SseEmitter registerConcentrator(String tag,String key);

    void send(String tag,String key,Object message);


}
