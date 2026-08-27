package com.hjmicro.service;


import com.hjmicro.domain.PageResult;
import com.hjmicro.domain.request.MachineListRequest;
import com.hjmicro.fluent.entity.MachineInfoEntity;
import org.springframework.data.domain.PageRequest;

import java.util.List;

public interface MachineService {

    PageResult<MachineInfoEntity> getMachines(MachineListRequest request);

}
