# Agent SDR Execution Service

从 [GIN4869623/Agent_SDR](https://github.com/GIN4869623/Agent_SDR) 的提交 `fdb5673b57785335b01ff92c192e13e2d039e455` 提取的本地执行面。上游仓库仅作只读来源，本目录不与其远端仓库绑定。

## 保留内容

- `src/agent/`：LLM Agent 循环、上下文构建和工具编排。
- `src/mcp/`：将统一工具注册表暴露为 MCP SSE/stdio 服务的协议适配层。
- `hardware/`：UHD/USRP 扫频、收发、调制解调、GNU Radio 视频任务控制，以及受限 UHD 设备诊断。
- `skill/`：知识检索、扫频、Tone、文本收发、自适应发射和设备参数诊断等匹配规则、操作说明与 `allowed_tools`，不直接执行硬件。
- `core/`：会话记忆与百炼知识应用客户端。
- `static/`：独立 Web 控制台。
- `data/`、`scripts/`：SDR 资料与 PDF 文本提取脚本。
- `tools/`：唯一的 ToolSpec、Pydantic 请求模型、Handler 与 ToolRegistry；Python Agent Loop、MCP 和 HTTP 直连工具接口共用。
- `src/operation_idempotency.py`：MCP 与 HTTP 共享的 Redis operationId 状态机，防止跨通道重复执行硬件动作。

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

访问 `http://127.0.0.1:8000`。健康检查为 `/api/health`，硬件状态为 `/api/hardware_status`，MCP 挂载点为 `/mcp`。HTTP 工具降级入口为 `POST /api/tools/execute`，操作状态查询为 `GET /api/operations/{operationId}`。

UHD Python 绑定不通过本项目的 pip 依赖安装，需要按操作系统和 USRP 版本单独配置。未安装 UHD 或未连接设备时，服务仍可启动，但硬件接口只返回离线/驱动缺失状态。

`query_usrp_device_parameters` 工具只允许 `summary`、`find_devices`、`probe_device`、`get_uhd_version` 和 `ping_device` 五类动作。服务端以 `shell=false` 调用固定的 `uhd_config_info`、`uhd_find_devices`、`uhd_usrp_probe` 或 `ping` 参数列表，拒绝任意命令文本、非法 IP，并对每个命令设置超时和输出上限。

内部 Agent Loop 不经过 MCP 网络调用：规则命中 Skill 后，只把该 Skill 的 `allowed_tools` 转成 OpenAI Tool Schema 交给模型；即使模型伪造其他已注册工具名，`ToolRegistry` 仍返回 `TOOL_NOT_ALLOWED`，不会进入 Handler。注册表随后使用 Pydantic 校验模型参数、通过 Redis 原子认领 operationId，最后执行硬件 Handler。FastMCP 和 `/api/tools/execute` 不经过 Python Skill 匹配，但仍共享同一套 Pydantic 参数策略、注册表和幂等状态机。

当前射频策略按实验室 NI USRP-2943R 配置：中心频率限制为 1.2-6.0 GHz，TX 增益 0-31.5 dB，RX 增益 0-37.5 dB，归一化幅度 0.01-1.0，调制方式只允许 2-FSK/BPSK/QPSK/16-QAM。扫描还限制总采样点数，文本发送限制每符号采样点数，避免单字段合法但组合后造成过大内存分配。若更换射频前端，应同步修改 `tools/policy.py` 并重新测试，而不是只修改 Skill 文案。

operationId 由调用方生成，MCP 重试和 HTTP 降级必须保持不变。状态机使用 `RUNNING`、`SUCCESS`、`FAILED`、`UNKNOWN` 四种核心状态：重复的已完成请求回放原结果，运行中的请求不再次执行；同一编号用于不同工具或参数时拒绝；执行租约过期时标记 `UNKNOWN`，不猜测硬件是否完成。Redis 不可用时硬件执行 fail-closed。

```powershell
$env:REDIS_URL='redis://127.0.0.1:6379/0'
$env:OPERATION_RECORD_TTL_SECONDS='604800'
$env:OPERATION_LEASE_SECONDS='300'
```

## 与 Java 控制面的关系

Spring Boot 默认通过 `AGENT_SDR_BASE_URL=http://127.0.0.1:8000` 调用本服务；需要直接使用 MCP 时，再启用 `SDR_MCP_ENABLED=true`。
