package com.hjmicro.server.service;

import com.hjmicro.ServiceInterface;
import com.hjmicro.domain.dto.AgentEventDTO;

public interface AgentEventService extends ServiceInterface {

    Boolean reportEvent(AgentEventDTO event);
}
