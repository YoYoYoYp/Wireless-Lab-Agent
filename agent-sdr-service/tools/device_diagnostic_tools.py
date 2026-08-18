from __future__ import annotations

from typing import Literal

from pydantic import Field

from hardware.uhd_diagnostics import UhdDiagnosticRunner
from tools import ToolInput, ToolSpec


class DeviceDiagnosticsRequest(ToolInput):
    action: Literal[
        "summary",
        "find_devices",
        "probe_device",
        "get_uhd_version",
        "ping_device",
    ] = Field(default="summary", description="受限诊断动作；默认执行UHD版本、设备发现和设备参数探测。")
    device_ip: str | None = Field(default=None, description="USRP管理地址；不填时使用服务端USRP_IP。")


def build_device_diagnostic_tools(runner: UhdDiagnosticRunner) -> list[ToolSpec]:
    return [
        ToolSpec(
            name="query_usrp_device_parameters",
            description=(
                "通过固定白名单的UHD命令查询USRP型号、序列号、主板、射频子板、UHD版本和设备连通性。"
                "不接受任意终端命令。"
            ),
            schema_model=DeviceDiagnosticsRequest,
            handler=lambda payload: runner.query(**payload.model_dump()),
            category="hardware",
        )
    ]
