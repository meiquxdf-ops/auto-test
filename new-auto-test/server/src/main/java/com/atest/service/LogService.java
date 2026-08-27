package com.atest.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.atest.config.AtestProperties;
import com.atest.domain.ExecutionLogEntity;
import com.atest.domain.TaskExecutionEntity;
import com.atest.repo.ExecutionLogRepository;
import com.atest.repo.TaskExecutionRepository;
import com.atest.sse.ExecutionSseService;
import com.atest.web.dto.LogLineView;
import com.atest.web.dto.LogPageView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Per execution log store with a 5MB tail cap; dropped lines flip truncated=true forever. */
@Slf4j
@Service
public class LogService {

    private static final int MAX_LINE_CHARS = 60000;

    private final AtestProperties props;
    private final ExecutionLogRepository logRepository;
    private final TaskExecutionRepository executionRepository;
    private final ExecutionSseService executionSse;

    public LogService(AtestProperties props,
                      ExecutionLogRepository logRepository,
                      TaskExecutionRepository executionRepository,
                      ExecutionSseService executionSse) {
        this.props = props;
        this.logRepository = logRepository;
        this.executionRepository = executionRepository;
        this.executionSse = executionSse;
    }

    /**
     * Appends agent lines starting at {@code fromSeq}. Sequences already stored are ignored, which
     * makes the agent free to resend after a reconnect.
     *
     * @return the highest sequence the server now holds (the ack the agent waits for)
     */
    @Transactional
    public int append(TaskExecutionEntity exec, int fromSeq, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return exec.getLogSeq();
        }
        Instant now = Instant.now();
        List<ExecutionLogEntity> batch = new ArrayList<>();
        List<LogLineView> published = new ArrayList<>();
        long addedBytes = 0;
        String lastLine = exec.getLastLine();
        int seq = Math.max(fromSeq, 1);

        for (String raw : lines) {
            int currentSeq = seq++;
            if (currentSeq <= exec.getLogSeq()) {
                continue;
            }
            String line = raw == null ? "" : raw;
            if (line.length() > MAX_LINE_CHARS) {
                line = line.substring(0, MAX_LINE_CHARS) + "…[line truncated]";
            }
            int bytes = line.getBytes(StandardCharsets.UTF_8).length + 1;
            ExecutionLogEntity entity = new ExecutionLogEntity();
            entity.setExecuteId(exec.getExecuteId());
            entity.setSeq(currentSeq);
            entity.setLine(line);
            entity.setBytes(bytes);
            entity.setTs(now);
            batch.add(entity);
            published.add(new LogLineView(currentSeq, line, now));
            addedBytes += bytes;
            if (!line.isBlank()) {
                lastLine = line;
            }
            if (currentSeq > exec.getLogSeq()) {
                exec.setLogSeq(currentSeq);
            }
        }

        if (batch.isEmpty()) {
            return exec.getLogSeq();
        }

        logRepository.saveAll(batch);
        exec.setLogBytes(exec.getLogBytes() + addedBytes);
        exec.setLastLine(lastLine);
        exec.setUpdatedAt(now);
        if (exec.getLogMinSeq() == 0) {
            exec.setLogMinSeq(batch.get(0).getSeq());
        }
        boolean trimmed = trimToCap(exec);
        executionRepository.save(exec);

        executionSse.publishLines(exec.getExecuteId(), published);
        if (trimmed) {
            executionSse.publishTruncated(exec.getExecuteId(), exec.getLogMinSeq(), exec.getLogBytes());
        }
        return exec.getLogSeq();
    }

    private boolean trimToCap(TaskExecutionEntity exec) {
        long cap = props.getLogs().getMaxBytesPerExecution();
        if (cap <= 0 || exec.getLogBytes() <= cap) {
            return false;
        }
        int batchSize = Math.max(50, props.getLogs().getTrimBatchSize());
        boolean trimmed = false;
        while (exec.getLogBytes() > cap) {
            List<ExecutionLogEntity> oldest = logRepository.findOldest(exec.getExecuteId(),
                    PageRequest.of(0, batchSize));
            if (oldest.isEmpty()) {
                break;
            }
            long freed = 0;
            for (ExecutionLogEntity e : oldest) {
                freed += e.getBytes();
            }
            logRepository.deleteAllInBatch(oldest);
            exec.setLogBytes(Math.max(0, exec.getLogBytes() - freed));
            trimmed = true;
            if (oldest.size() < batchSize) {
                break;
            }
        }
        if (trimmed) {
            exec.setTruncated(true);
            exec.setLogMinSeq(logRepository.minSeq(exec.getExecuteId()));
        }
        return trimmed;
    }

    @Transactional(readOnly = true)
    public LogPageView page(TaskExecutionEntity exec, int from, int limit) {
        int size = limit <= 0 ? 1000 : Math.min(limit, props.getLogs().getMaxPageSize());
        int fromSeq = Math.max(from, 0);
        List<ExecutionLogEntity> rows = logRepository.findPage(exec.getExecuteId(), fromSeq,
                PageRequest.of(0, size));
        List<LogLineView> lines = rows.stream()
                .map(r -> new LogLineView(r.getSeq(), r.getLine(), r.getTs()))
                .toList();
        int nextSeq = lines.isEmpty() ? fromSeq : lines.get(lines.size() - 1).seq() + 1;
        boolean hasMore = !lines.isEmpty() && lines.get(lines.size() - 1).seq() < exec.getLogSeq();
        return new LogPageView(
                exec.getExecuteId(),
                fromSeq,
                nextSeq,
                exec.getLogMinSeq(),
                exec.getLogSeq(),
                exec.isTruncated(),
                exec.getLogBytes(),
                props.getLogs().getMaxBytesPerExecution(),
                hasMore,
                exec.getStatus().wire(),
                exec.getStatus().isTerminal(),
                lines);
    }

    @Transactional(readOnly = true)
    public List<LogLineView> rawPage(String executeId, int from, int limit) {
        int size = limit <= 0 ? 1000 : Math.min(limit, props.getLogs().getMaxPageSize());
        return logRepository.findPage(executeId, Math.max(from, 0), PageRequest.of(0, size)).stream()
                .map(r -> new LogLineView(r.getSeq(), r.getLine(), r.getTs()))
                .toList();
    }
}
