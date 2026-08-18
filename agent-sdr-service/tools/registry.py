"""Build the single tool registry shared by the local Agent Loop and MCP."""

from __future__ import annotations

from core.rag import LocalKnowledgeBase
from hardware.sdr_controller import SDRController
from hardware.uhd_diagnostics import UhdDiagnosticRunner
from hardware.video_stream_controller import VideoStreamController
from tools import ToolRegistry, ToolSpec
from tools.device_diagnostic_tools import build_device_diagnostic_tools
from tools.knowledge_tools import build_knowledge_tools
from tools.physical_tools import build_physical_tools
from tools.transmit_tools import build_transmit_tools
from tools.video_tools import build_video_tools


def create_tool_registry(
    controller: SDRController,
    knowledge_base: LocalKnowledgeBase,
    video_controller: VideoStreamController | None = None,
    diagnostic_runner: UhdDiagnosticRunner | None = None,
) -> ToolRegistry:
    """Create the canonical registry without coupling local execution to MCP."""
    specs: list[ToolSpec] = []
    specs.extend(build_physical_tools(controller))
    specs.extend(build_transmit_tools(controller))
    specs.extend(build_knowledge_tools(knowledge_base))
    if diagnostic_runner is not None:
        specs.extend(build_device_diagnostic_tools(diagnostic_runner))
    if video_controller is not None:
        specs.extend(build_video_tools(video_controller))
    return ToolRegistry(specs)
