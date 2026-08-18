"""Expose the canonical local ToolRegistry through FastMCP.

The Python Agent Loop calls ToolRegistry directly. Only external clients use
this adapter and the MCP transport, so protocol concerns never leak into local
tool execution.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any, Callable

import yaml
from mcp.server.fastmcp import FastMCP

from core.rag import LocalKnowledgeBase
from hardware.sdr_controller import SDRController
from tools import ToolRegistry


def create_sdr_mcp(
    tool_registry: ToolRegistry,
    controller: SDRController,
    knowledge_base: LocalKnowledgeBase,
) -> FastMCP:
    """Create an MCP server that delegates every tool call to ToolRegistry."""
    mcp = FastMCP(
        name="Agent SDR Platform",
        instructions=(
            "Agent SDR Platform — manage USRP X300 software-defined radio hardware. "
            "Use tools to scan spectrum, transmit/receive text with FSK/BPSK/QPSK/16-QAM, "
            "generate and visualize tones, stream video via GNU Radio, and query the "
            "SDR knowledge base or inspect USRP/UHD runtime parameters with allow-listed diagnostics. "
            "Default center frequency: 2.4 GHz. Default tone frequency: 100 kHz."
        ),
    )

    def expose(name: str) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
        spec = tool_registry.get_tool(name)
        if spec is None:
            raise ValueError(f"无法通过 MCP 暴露未注册工具: {name}")
        return mcp.tool(name=spec.name, description=spec.description)

    async def invoke(name: str, payload: dict[str, Any]) -> str:
        result = await tool_registry.execute(name, payload)
        return json.dumps(result, ensure_ascii=False, indent=2)

    @expose("perform_physical_scan")
    async def perform_physical_scan(
        center_freq_hz: float = 2_400_000_000.0,
        bandwidth_hz: float = 1_000_000.0,
        duration_s: float = 0.2,
        chan: int = 0,
    ) -> str:
        return await invoke("perform_physical_scan", locals())

    @expose("tone_loopback_visualize")
    async def tone_loopback_visualize(
        center_freq_hz: float = 2_400_000_000.0,
        tone_freq_hz: float = 100_000.0,
        samp_rate: float = 1_000_000.0,
    ) -> str:
        return await invoke("tone_loopback_visualize", locals())

    @expose("stop_hardware_task")
    async def stop_hardware_task() -> str:
        return await invoke("stop_hardware_task", {})

    @expose("text_fsk_send_and_receive")
    async def text_fsk_send_and_receive(
        text: str,
        modulation_scheme: str = "2-FSK",
        center_freq_hz: float = 2_400_000_000.0,
        samp_rate: float = 1_000_000.0,
        sym_rate: float = 10_000.0,
        f_dev: float = 25_000.0,
        tx_gain: float = 20.0,
        rx_gain: float = 20.0,
        amp: float = 0.6,
        packet_repetitions: int = 3,
    ) -> str:
        return await invoke("text_fsk_send_and_receive", locals())

    @expose("adaptive_modulation_transmit")
    async def adaptive_modulation_transmit(
        text: str,
        center_freq_hz: float = 2_400_000_000.0,
        probe_text: str = "channel_probe",
    ) -> str:
        return await invoke("adaptive_modulation_transmit", locals())

    @expose("auto_optimal_transmit")
    async def auto_optimal_transmit(
        text: str = "",
        modulation_scheme: str = "2-FSK",
        center_freq_hz: float = 2_400_000_000.0,
        search_span_hz: float = 30_000_000.0,
        enable_tone: bool = False,
        tone_freq_hz: float = 100_000.0,
        use_adaptive_modulation: bool = False,
    ) -> str:
        return await invoke("auto_optimal_transmit", locals())

    @expose("search_sdr_knowledge")
    async def search_sdr_knowledge(query: str, top_k: int = 3) -> str:
        return await invoke("search_sdr_knowledge", locals())

    if tool_registry.has_tool("query_usrp_device_parameters"):

        @expose("query_usrp_device_parameters")
        async def query_usrp_device_parameters(
            action: str = "summary",
            device_ip: str | None = None,
        ) -> str:
            return await invoke("query_usrp_device_parameters", locals())

    if tool_registry.has_tool("start_video_stream"):

        @expose("start_video_stream")
        async def start_video_stream() -> str:
            return await invoke("start_video_stream", {})

        @expose("stop_video_stream")
        async def stop_video_stream() -> str:
            return await invoke("stop_video_stream", {})

        @expose("video_stream_status")
        async def video_stream_status() -> str:
            return await invoke("video_stream_status", {})

    @mcp.resource(
        uri="sdr://hardware/status",
        name="USRP Hardware Status",
        description="USRP 当前连接状态、中心频率、采样率、增益和调制方式。",
    )
    async def hardware_status() -> str:
        snapshot = controller.get_hardware_snapshot()
        return json.dumps(snapshot, ensure_ascii=False, indent=2)

    @mcp.resource(
        uri="sdr://hardware/visualization",
        name="Visualization Snapshot",
        description="当前可视化窗口的 IQ 波形和 FFT 频谱实时数据。",
    )
    async def visualization_snapshot() -> str:
        snapshot = controller.get_visualization_snapshot()
        for key in (
            "iq_inphase",
            "iq_quadrature",
            "fft_freq_khz",
            "fft_magnitude_db",
        ):
            if key in snapshot and hasattr(snapshot[key], "tolist"):
                snapshot[key] = snapshot[key].tolist()
        return json.dumps(snapshot, ensure_ascii=False, indent=2)

    @mcp.resource(
        uri="sdr://knowledge/{query}",
        name="SDR Knowledge Query",
        description="从百炼云端知识库检索 SDR 相关文档资料。",
    )
    async def knowledge_query(query: str) -> str:
        result = knowledge_base.query(query)
        return result or f"知识库未检索到与「{query}」匹配的内容。"

    skills_dir = Path(__file__).resolve().parent.parent.parent / "skill" / "skills"
    for md_path in sorted(skills_dir.glob("*.md")):
        content = md_path.read_text(encoding="utf-8")
        match = re.match(r"^---\s*\n(.*?)\n---\s*\n(.*)", content, re.DOTALL)
        if not match:
            continue
        frontmatter = yaml.safe_load(match.group(1)) or {}
        name = frontmatter.get("name", md_path.stem)
        description = frontmatter.get("description", name)
        body = re.sub(r"^#\s+.*\n+", "", match.group(2).strip()).strip()

        def make_prompt(prompt_body: str = body):
            async def prompt_fn() -> str:
                return prompt_body

            return prompt_fn

        mcp.prompt(name=name, description=description)(make_prompt())

    @mcp.prompt(
        name="sdr-diagnose",
        description="硬件结果诊断 — 分析 SNR、解码状态并给出参数建议。",
    )
    async def prompt_diagnose() -> str:
        return (
            "请根据刚才的硬件执行结果，分析信道质量（SNR、接收功率），"
            "判断当前调制方式是否合适，如果不合适请给出具体的参数调整建议。"
        )

    return mcp
