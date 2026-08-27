package com.hjmicro.controller;


import com.alibaba.fastjson.JSON;
import com.hjmicro.domain.PageResult;
import com.hjmicro.domain.request.MachineListRequest;
import com.hjmicro.fluent.entity.MachineInfoEntity;
import com.hjmicro.service.MachineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/machine")
@RestController
@CrossOrigin(originPatterns = "*")
public class MachineController {

    @Autowired
    public MachineService machineService;


    @RequestMapping("/getMachineList")
    public PageResult getMachineList(@RequestBody MachineListRequest request) {
        PageResult<MachineInfoEntity> machines = machineService.getMachines(request);

        System.out.println(JSON.toJSONString(machines));
        return machines;
    }

}
