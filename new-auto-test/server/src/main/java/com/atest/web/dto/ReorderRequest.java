package com.atest.web.dto;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReorderRequest {

    /** pending task ids in the wanted order */
    private List<Long> taskIds;

    /** alias so the UI may post {"ids":[...]} */
    private List<Long> ids;

    public List<Long> resolve() {
        if (taskIds != null && !taskIds.isEmpty()) {
            return taskIds;
        }
        return ids == null ? List.of() : ids;
    }
}
