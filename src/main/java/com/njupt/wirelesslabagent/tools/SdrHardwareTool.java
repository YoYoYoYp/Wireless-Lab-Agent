package com.njupt.wirelesslabagent.tools;

import com.njupt.wirelesslabagent.service.AgentSdrClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class SdrHardwareTool {

    private final AgentSdrClient agentSdrClient;

    public SdrHardwareTool(AgentSdrClient agentSdrClient) {
        this.agentSdrClient = agentSdrClient;
    }

    @Tool(description = "执行 USRP 硬件实验指令。用于扫频、Tone、文本收发、固定/自适应调制、认知选频等动作；必须原样传入用户的频率、增益、调制方式和文本要求。")
    public String executeSdrInstruction(
            @ToolParam(description = "完整、无歧义的硬件实验指令，包含用户给出的全部参数") String instruction) {
        return agentSdrClient.executeInstruction(instruction);
    }

    @Tool(description = "查询 Agent_SDR 服务和 USRP 当前连接、运行任务、频率、增益及诊断状态。")
    public String getSdrHardwareStatus() {
        return agentSdrClient.hardwareStatus().toPrettyString();
    }

    @Tool(description = "停止当前占用 USRP 的后台任务并释放硬件资源。")
    public String stopSdrHardwareTask() {
        return agentSdrClient.stopHardwareTask().toPrettyString();
    }
}
