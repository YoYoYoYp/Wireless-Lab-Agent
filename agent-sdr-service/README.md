# Agent SDR Execution Service

从 [GIN4869623/Agent_SDR](https://github.com/GIN4869623/Agent_SDR) 的提交 `fdb5673b57785335b01ff92c192e13e2d039e455` 提取的本地执行面。上游仓库仅作只读来源，本目录不与其远端仓库绑定。

## 保留内容

- `src/agent/`：LLM Agent 循环、上下文构建和工具编排。
- `src/mcp/`：将统一工具注册表暴露为 MCP SSE/stdio 服务的协议适配层。
- `hardware/`：UHD/USRP 扫频、收发、调制解调、GNU Radio 视频任务控制，以及受限 UHD 设备诊断。
- `skill/`：知识检索、扫频、Tone、文本收发、自适应发射和设备参数诊断等匹配规则与操作说明，不执行硬件。
- `core/`：会话记忆与百炼知识应用客户端。
- `static/`：独立 Web 控制台。
- `data/`、`scripts/`：SDR 资料与 PDF 文本提取脚本。
- `tools/`：唯一的 ToolSpec、Pydantic 请求模型、Handler 与 ToolRegistry；Python Agent Loop 和 MCP 共用。

未保留旧 LangGraph、旧 Agent、旧 MCP Server、演示视频、截图和 IDE/个人配置。

LLM 默认连接实验室本地 OpenAI 兼容服务，`think` 与 `fast` 模式都使用 Qwen3.5-122B；两种模式仅保留温度和历史窗口等推理参数差异，不再切换到其他模型。

## 启动

```powershell
Set-Location .\agent-sdr-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
Copy-Item .env.example .env
uvicorn main:app --host 127.0.0.1 --port 8000
```

默认模型环境变量：

```powershell
$env:LOCAL_LLM_BASE_URL='http://127.0.0.1:11434/v1'
$env:LOCAL_LLM_API_KEY='ollama'
$env:LOCAL_LLM_MODEL='qwen3.5:122b'
```

访问 `http://127.0.0.1:8000`。健康检查为 `/api/health`，硬件状态为 `/api/hardware_status`，MCP 挂载点为 `/mcp`。

UHD Python 绑定不通过本项目的 pip 依赖安装，需要按操作系统和 USRP 版本单独配置。未安装 UHD 或未连接设备时，服务仍可启动，但硬件接口只返回离线/驱动缺失状态。

`query_usrp_device_parameters` 工具只允许 `summary`、`find_devices`、`probe_device`、`get_uhd_version` 和 `ping_device` 五类动作。服务端以 `shell=false` 调用固定的 `uhd_config_info`、`uhd_find_devices`、`uhd_usrp_probe` 或 `ping` 参数列表，拒绝任意命令文本、非法 IP，并对每个命令设置超时和输出上限。

内部 Agent Loop 不经过 MCP 网络调用，而是直接调用统一 `ToolRegistry`；注册表先使用 Pydantic 校验模型生成的参数，再执行硬件 Handler。FastMCP 仅作为对外适配层，把同一注册表提供给 Java 控制智能体和其他 MCP 客户端，避免维护第二套工具实现。

## 与 Java 控制面的关系

Spring Boot 默认通过 `AGENT_SDR_BASE_URL=http://127.0.0.1:8000` 调用本服务；需要直接使用 MCP 时，再启用 `SDR_MCP_ENABLED=true`。
