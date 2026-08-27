package com.atest.web;

import java.util.List;

import com.atest.service.TimelineService;
import com.atest.web.dto.TimelineItemView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/timeline")
public class TimelineController {

    private final TimelineService timelineService;

    public TimelineController(TimelineService timelineService) {
        this.timelineService = timelineService;
    }

    @GetMapping
    public List<TimelineItemView> query(@RequestParam(required = false) String agentId,
                                        @RequestParam(required = false) String executeId,
                                        @RequestParam(defaultValue = "200") int limit) {
        return timelineService.query(agentId, executeId, limit);
    }
}
