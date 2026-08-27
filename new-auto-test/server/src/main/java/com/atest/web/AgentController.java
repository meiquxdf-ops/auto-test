package com.atest.web;

import java.util.List;
import java.util.Map;

import com.atest.service.AgentService;
import com.atest.web.dto.AgentView;
import com.atest.web.dto.PatchAgentRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping
    public List<AgentView> list(@RequestParam(required = false) String status,
                                @RequestParam(required = false) String keyword) {
        return agentService.list(status, keyword);
    }

    @GetMapping("/{agentId}")
    public AgentView get(@PathVariable String agentId) {
        return agentService.get(agentId);
    }

    @PatchMapping("/{agentId}")
    public AgentView patch(@PathVariable String agentId, @RequestBody PatchAgentRequest request) {
        return agentService.patch(agentId, request);
    }

    @PutMapping("/{agentId}")
    public AgentView put(@PathVariable String agentId, @RequestBody PatchAgentRequest request) {
        return agentService.patch(agentId, request);
    }

    @PostMapping("/{agentId}/restart")
    public Map<String, Object> restart(@PathVariable String agentId) {
        return agentService.restart(agentId);
    }

    @PostMapping("/{agentId}/stop")
    public Map<String, Object> stop(@PathVariable String agentId) {
        return agentService.stop(agentId);
    }
}
