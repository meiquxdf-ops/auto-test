package com.atest.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 最近被 hello 拒绝的 displayTag（tag_conflict）。
 *
 * <p>install.sh 只能看到「20s 内没连上」，分不清是重名还是网络不通；
 * 而拒绝动作发生在 Server 自己身上。这里记一份内存里的近期拒绝记录，
 * 「SSH 代装」失败后按安装窗口回查，就能把 tag_conflict 精确地报给页面。
 */
@Component
public class TagConflictTracker {

    private static final Duration RETENTION = Duration.ofMinutes(10);

    private final Map<String, Instant> recent = new ConcurrentHashMap<>();

    public void record(String tag) {
        if (tag == null || tag.isBlank()) {
            return;
        }
        Instant cutoff = Instant.now().minus(RETENTION);
        recent.values().removeIf(t -> t.isBefore(cutoff));
        recent.put(tag.trim(), Instant.now());
    }

    /** since 之后是否有该 tag 的注册被以 tag_conflict 拒绝 */
    public boolean conflictSince(String tag, Instant since) {
        if (tag == null) {
            return false;
        }
        Instant t = recent.get(tag.trim());
        return t != null && !t.isBefore(since);
    }
}
