package com.atest.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.atest.domain.TaskExecutionEntity;
import com.atest.service.ExecutionService;
import com.atest.service.LogService;
import com.atest.sse.AgentSseService;
import com.atest.sse.ExecutionSseService;
import com.atest.web.dto.LogLineView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/sse")
public class SseController {

    private final AgentSseService agentSse;
    private final ExecutionSseService executionSse;
    private final ExecutionService executionService;
    private final LogService logService;

    public SseController(AgentSseService agentSse,
                         ExecutionSseService executionSse,
                         ExecutionService executionService,
                         LogService logService) {
        this.agentSse = agentSse;
        this.executionSse = executionSse;
        this.executionService = executionService;
        this.logService = logService;
    }

    @GetMapping(value = "/agents", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter agents() {
        return agentSse.subscribe();
    }

    /**
     * Live log tail. The subscription is registered before the backlog is read, so lines produced
     * during the handover are replayed in order instead of being lost.
     */
    @GetMapping(value = "/exec/{id}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter exec(@PathVariable String id,
                           @RequestParam(defaultValue = "0") int from,
                           @RequestParam(defaultValue = "5000") int backlogLimit) {
        TaskExecutionEntity exec = executionService.require(id);
        ExecutionSseService.Subscription sub = executionSse.subscribe(exec.getExecuteId(), from);

        Map<String, Object> head = new LinkedHashMap<>(executionService.statusPayload(exec));
        head.put("minSeq", exec.getLogMinSeq());
        head.put("from", from);
        sub.sendEvent("status", head);

        List<LogLineView> backlog = logService.rawPage(exec.getExecuteId(), from, backlogLimit);
        sub.flushBacklog(backlog);

        if (exec.isTruncated()) {
            executionSse.publishTruncated(exec.getExecuteId(), exec.getLogMinSeq(), exec.getLogBytes());
        }
        return sub.emitter();
    }
}
