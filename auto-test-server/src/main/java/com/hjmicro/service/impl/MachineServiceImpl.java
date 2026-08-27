package com.hjmicro.service.impl;

import cn.org.atool.fluent.mybatis.model.StdPagedList;
import com.hjmicro.domain.PageResult;
import com.hjmicro.domain.request.MachineListRequest;
import com.hjmicro.fluent.entity.MachineInfoEntity;
import com.hjmicro.fluent.mapper.MachineInfoMapper;
import com.hjmicro.service.MachineService;
import org.checkerframework.checker.units.qual.A;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MachineServiceImpl implements MachineService {


    @Autowired
    private MachineInfoMapper machineInfoMapper;


    @Override
    public PageResult<MachineInfoEntity> getMachines(MachineListRequest request) {
        StdPagedList<MachineInfoEntity> machineInfoEntityStdPagedList =
                machineInfoMapper.stdPagedEntity(request.getMachineInfoEntity().asQuery().limit(request.getRequest().getPageNumber(), request.getRequest().getPageSize()));
        return new PageResult<>(machineInfoEntityStdPagedList.getData(),
                machineInfoEntityStdPagedList.getTotal(),
                request.getRequest().getPageNumber()
                ,request.getRequest().getPageSize());
    }

}
