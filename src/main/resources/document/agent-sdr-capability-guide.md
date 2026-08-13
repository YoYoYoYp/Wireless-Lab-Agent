# 实验流程

## 执行面与调用方式

Java 控制面负责意图分类、RAG、会话和工具选择；Python `agent-sdr-service` 负责 MCP、UHD 和 USRP 实际执行。启用 MCP 时，Spring AI 直接注册 Python 服务暴露的工具；未启用 MCP 时，Java 的 `executeSdrInstruction` 将完整自然语言指令发送到 Python `/api/chat`。两条路径最终都必须以真实硬件返回值为准。

## MCP 工具目录

### perform_physical_scan

只接收不发射。默认中心频率 2.4 GHz、扫描带宽 1 MHz、采样 0.2 秒、通道 0。返回采集状态、功率或候选频点等结果。一次扫描只能说明采样时刻的频谱情况，不能证明频段长期空闲。

### tone_loopback_visualize

启动持续 Tone 后台任务并更新 IQ/FFT 可视化。默认中心频率 2.4 GHz、基带 Tone 100 kHz、采样率 1 MHz。它会占用 USRP，观察结束后必须调用 `stop_hardware_task`。

### text_fsk_send_and_receive

用于固定调制文本收发，`text` 必填。支持 2-FSK、BPSK、QPSK 和 16-QAM；默认中心频率 2.4 GHz、采样率 1 MHz、符号率 10 kBaud、收发增益 20 dB、幅度 0.6、重复发包 3 次。结果应包含真实解码文本、调制方式、SNR/功率或明确错误。

### adaptive_modulation_transmit

`text` 必填。先用默认 `channel_probe` 探测链路，再根据实测 SNR 选择 BPSK、QPSK 或 16-QAM。探测、发送或接收任一阶段失败，都应终止流程并报告失败阶段。

### auto_optimal_transmit

在目标中心频率附近搜索干扰较低的候选频点，再选择执行文本发送、自适应调制或 Tone。默认中心频率 2.4 GHz、搜索跨度 30 MHz、固定调制 2-FSK。`enable_tone` 和 `use_adaptive_modulation` 默认关闭。

### hardware_status 与 stop_hardware_task

`hardware_status` 返回 UHD 是否存在、USRP 是否连接、当前任务、中心频率、采样率、增益和诊断信息。`stop_hardware_task` 负责停止后台流并释放设备。状态查询和停止属于实时工具调用，不应该从向量库猜测。

### 视频流工具

`start_video_stream`、`stop_video_stream` 和 `video_stream_status` 管理外部 GNU Radio 视频脚本。使用前必须配置 `GR_VIDEO_SCRIPT` 和 `GR_PYTHON`；缺少脚本、GNU Radio、显示环境或 USRP 时应直接报告错误。

## 工具选择规则

- “什么是、为什么、规格是多少、步骤是什么”属于稳定知识，先走 RAG。
- “现在是否在线、当前频率是多少”调用状态工具。
- “扫描、发射、停止、切换调制”调用硬件工具。
- 用户没给出必填文本或高风险发射参数时先确认。
- 工具返回 `error`、`offline` 或 `connected=false` 时如实呈现，禁止补造测量结果。
