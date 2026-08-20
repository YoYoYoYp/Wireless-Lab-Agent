package com.njupt.wirelesslabagent.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.njupt.wirelesslabagent.service.AgentSdrClient;
import com.njupt.wirelesslabagent.config.SdrResilienceProperties;
import com.njupt.wirelesslabagent.tools.SdrHardwareTool;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolTest {

    @Test
    void reportsAgentSdrOfflineWithoutInventingHardwareResult() {
        AgentSdrClient client = new AgentSdrClient(
                RestClient.builder(), new ObjectMapper(), new SdrResilienceProperties(),
                "http://127.0.0.1:1", "think");
        SdrHardwareTool tool = new SdrHardwareTool(client);

        String result = tool.getSdrHardwareStatus();

        assertTrue(result.contains("error"));
        assertTrue(result.contains("connected"));
    }
}
