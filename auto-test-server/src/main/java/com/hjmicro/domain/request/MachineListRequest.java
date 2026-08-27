package com.hjmicro.domain.request;

import com.hjmicro.domain.PageRequest;
import com.hjmicro.fluent.entity.MachineInfoEntity;
import lombok.Data;

@Data
public class MachineListRequest {

    private PageRequest request;

    private MachineInfoEntity machineInfoEntity;

}
