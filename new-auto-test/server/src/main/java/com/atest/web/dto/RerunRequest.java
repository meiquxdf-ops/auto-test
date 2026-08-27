package com.atest.web.dto;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RerunRequest {

    /** inplace: reset the same executions; new: clone the task into a fresh one */
    private String mode = "new";

    /** optional subset of executeIds / agentIds to rerun, empty means all */
    private List<String> targets;

    private String operator;
}
