package com.hjmicro.service.impl;

import com.hjmicro.service.SseService;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class SseServiceImpl implements SseService {

    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, List<SseEmitter>>> emitterMap = new ConcurrentHashMap<>();

    @Override
    public SseEmitter registerConcentrator(String tag, String key) {
        SseEmitter sseEmitter = new SseEmitter(0L);

        emitterMap.computeIfAbsent(tag, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                .add(sseEmitter);

        sseEmitter.onCompletion(() -> unregister(tag, key, sseEmitter));
        sseEmitter.onTimeout(() -> unregister(tag, key, sseEmitter));
        sseEmitter.onError((t) -> unregister(tag, key, sseEmitter));

        return sseEmitter;
    }

    @Override
    public void send(String tag, String key, Object message) {
        if (message == null) {
            System.out.println("发送消息为空");
            return;
        }

        ConcurrentHashMap<String, List<SseEmitter>> tagMap = emitterMap.get(tag);
        if (tagMap != null) {
            List<SseEmitter> sseEmitterList = tagMap.get(key);
            if (sseEmitterList != null) {
                Iterator<SseEmitter> iterator = sseEmitterList.iterator();
                List<SseEmitter> remove = new ArrayList<>();
                while (iterator.hasNext()) {
                    SseEmitter sseEmitter = iterator.next();
                    try {
                        HashMap<Object, Object> msg = new HashMap<>();
                        msg.put("data", message);
                        sseEmitter.send(SseEmitter.event().name("message").data(msg));
                    } catch (Exception e) {
                        remove.add(sseEmitter);
                    }
                }
                sseEmitterList.removeAll(remove);
            }
        }
    }

    private void unregister(String tag, String key, SseEmitter sseEmitter) {
        ConcurrentHashMap<String, List<SseEmitter>> tagMap = emitterMap.get(tag);
        if (tagMap == null) {
            return;
        }
        List<SseEmitter> sseEmitters = tagMap.get(key);
        if (sseEmitters == null) {
            return;
        }
        sseEmitters.remove(sseEmitter);
        if (sseEmitters.isEmpty()) {
            tagMap.remove(key, sseEmitters);
        }
        if (tagMap.isEmpty()) {
            emitterMap.remove(tag, tagMap);
        }
    }




}
