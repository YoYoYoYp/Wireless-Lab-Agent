package com.njupt.wirelesslabagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.njupt.wirelesslabagent.common.BaseResponse;
import com.njupt.wirelesslabagent.common.ResuitUtils;
import com.njupt.wirelesslabagent.service.AgentSdrClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/hardware")
public class HardwareController {

    private final AgentSdrClient agentSdrClient;

    public HardwareController(AgentSdrClient agentSdrClient) {
        this.agentSdrClient = agentSdrClient;
    }

    @GetMapping("/health")
    public BaseResponse<JsonNode> health() {
        return ResuitUtils.success(agentSdrClient.health());
    }

    @GetMapping("/status")
    public BaseResponse<JsonNode> status() {
        return ResuitUtils.success(agentSdrClient.hardwareStatus());
    }

    @GetMapping("/visualization")
    public BaseResponse<JsonNode> visualization() {
        return ResuitUtils.success(agentSdrClient.visualization());
    }

    @PostMapping("/stop")
    public BaseResponse<JsonNode> stop() {
        return ResuitUtils.success(agentSdrClient.stopHardwareTask());
    }
}
