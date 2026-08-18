from __future__ import annotations

import ipaddress
import locale
import os
import subprocess
import time
from typing import Any, Callable, List


CommandExecutor = Callable[[List[str], float], subprocess.CompletedProcess]


class UhdDiagnosticRunner:
    """Run a fixed allow-list of UHD diagnostic commands without a shell."""

    ALLOWED_ACTIONS = {
        "summary",
        "find_devices",
        "probe_device",
        "get_uhd_version",
        "ping_device",
    }

    def __init__(
        self,
        default_device_ip: str,
        *,
        timeout_s: float = 10.0,
        max_output_chars: int = 12_000,
        executor: CommandExecutor | None = None,
    ) -> None:
        self.default_device_ip = self._validate_ip(default_device_ip)
        self.timeout_s = max(1.0, min(float(timeout_s), 30.0))
        self.max_output_chars = max(1_000, min(int(max_output_chars), 50_000))
        self.executor = executor or self._execute

    def query(self, action: str = "summary", device_ip: str | None = None) -> dict[str, Any]:
        normalized_action = str(action or "summary").strip().lower()
        if normalized_action not in self.ALLOWED_ACTIONS:
            return {
                "status": "error",
                "message": f"不支持的诊断动作: {action}",
                "allowed_actions": sorted(self.ALLOWED_ACTIONS),
            }

        try:
            target_ip = self._validate_ip(device_ip or self.default_device_ip)
        except ValueError as exc:
            return {"status": "error", "message": str(exc)}

        commands = self._commands_for(normalized_action, target_ip)
        results = [self._run_named(name, command) for name, command in commands]
        succeeded = sum(1 for result in results if result["success"])
        status = "success" if succeeded == len(results) else "partial" if succeeded else "error"
        return {
            "status": status,
            "message": f"UHD诊断完成：{succeeded}/{len(results)} 项成功。",
            "action": normalized_action,
            "device_ip": target_ip,
            "security": "仅执行服务端固定白名单命令，未启用shell。",
            "results": results,
        }

    def _commands_for(self, action: str, device_ip: str) -> list[tuple[str, list[str]]]:
        command_map: dict[str, tuple[str, list[str]]] = {
            "find_devices": ("find_devices", ["uhd_find_devices"]),
            "probe_device": (
                "probe_device",
                ["uhd_usrp_probe", "--args", f"addr={device_ip}"],
            ),
            "get_uhd_version": ("get_uhd_version", ["uhd_config_info", "--version"]),
            "ping_device": (
                "ping_device",
                ["ping", "-n", "1", device_ip]
                if os.name == "nt"
                else ["ping", "-c", "1", device_ip],
            ),
        }
        if action == "summary":
            return [
                command_map["get_uhd_version"],
                command_map["find_devices"],
                command_map["probe_device"],
            ]
        return [command_map[action]]

    def _run_named(self, name: str, command: list[str]) -> dict[str, Any]:
        started_at = time.perf_counter()
        try:
            completed = self.executor(command, self.timeout_s)
            stdout = self._truncate(str(completed.stdout or ""))
            stderr = self._truncate(str(completed.stderr or ""))
            return {
                "name": name,
                "success": completed.returncode == 0,
                "exit_code": int(completed.returncode),
                "duration_ms": round((time.perf_counter() - started_at) * 1_000, 2),
                "stdout": stdout,
                "stderr": stderr,
            }
        except FileNotFoundError:
            return {
                "name": name,
                "success": False,
                "exit_code": None,
                "duration_ms": round((time.perf_counter() - started_at) * 1_000, 2),
                "stdout": "",
                "stderr": f"命令不存在或不在PATH中: {command[0]}",
            }
        except subprocess.TimeoutExpired as exc:
            return {
                "name": name,
                "success": False,
                "exit_code": None,
                "duration_ms": round((time.perf_counter() - started_at) * 1_000, 2),
                "stdout": self._truncate(self._timeout_text(exc.stdout)),
                "stderr": f"命令执行超过 {self.timeout_s:.0f} 秒，已终止。",
            }
        except Exception as exc:
            return {
                "name": name,
                "success": False,
                "exit_code": None,
                "duration_ms": round((time.perf_counter() - started_at) * 1_000, 2),
                "stdout": "",
                "stderr": f"诊断命令执行失败: {exc}",
            }

    def _execute(self, command: list[str], timeout_s: float) -> subprocess.CompletedProcess[str]:
        kwargs: dict[str, Any] = {
            "capture_output": True,
            "text": True,
            "encoding": locale.getpreferredencoding(False),
            "errors": "replace",
            "timeout": timeout_s,
            "check": False,
            "shell": False,
        }
        if os.name == "nt":
            kwargs["creationflags"] = subprocess.CREATE_NO_WINDOW
        return subprocess.run(command, **kwargs)

    def _truncate(self, text: str) -> str:
        normalized = text.strip()
        if len(normalized) <= self.max_output_chars:
            return normalized
        return normalized[: self.max_output_chars] + "\n...[输出已截断]"

    @staticmethod
    def _timeout_text(value: str | bytes | None) -> str:
        if value is None:
            return ""
        if isinstance(value, bytes):
            return value.decode(errors="replace")
        return value

    @staticmethod
    def _validate_ip(value: str) -> str:
        try:
            return str(ipaddress.ip_address(str(value).strip()))
        except ValueError as exc:
            raise ValueError(f"无效的USRP IP地址: {value}") from exc
