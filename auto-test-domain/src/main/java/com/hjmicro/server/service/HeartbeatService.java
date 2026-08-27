package com.hjmicro.server.service;

import com.hjmicro.ServiceInterface;
import com.hjmicro.domain.dto.HeartbeatMessage;

public interface HeartbeatService extends ServiceInterface {

    boolean heartbeat(HeartbeatMessage message);


    boolean isOnline(String ip);

    boolean isIdle(String ip);
}
