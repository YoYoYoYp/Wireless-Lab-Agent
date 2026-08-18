from __future__ import annotations

import subprocess
import unittest

from hardware.uhd_diagnostics import UhdDiagnosticRunner


class UhdDiagnosticRunnerTest(unittest.TestCase):
    def test_rejects_unknown_action_without_running_process(self) -> None:
        calls: list[list[str]] = []

        def fake_executor(command: list[str], timeout_s: float) -> subprocess.CompletedProcess[str]:
            calls.append(command)
            return subprocess.CompletedProcess(command, 0, stdout="ok", stderr="")

        runner = UhdDiagnosticRunner("192.168.40.2", executor=fake_executor)
        result = runner.query("run_arbitrary_command")

        self.assertEqual("error", result["status"])
        self.assertEqual([], calls)

    def test_rejects_invalid_ip_without_running_process(self) -> None:
        calls: list[list[str]] = []

        def fake_executor(command: list[str], timeout_s: float) -> subprocess.CompletedProcess[str]:
            calls.append(command)
            return subprocess.CompletedProcess(command, 0, stdout="ok", stderr="")

        runner = UhdDiagnosticRunner("192.168.40.2", executor=fake_executor)
        result = runner.query("probe_device", "192.168.40.2 & whoami")

        self.assertEqual("error", result["status"])
        self.assertEqual([], calls)

    def test_probe_builds_fixed_argument_list_without_shell(self) -> None:
        calls: list[tuple[list[str], float]] = []

        def fake_executor(command: list[str], timeout_s: float) -> subprocess.CompletedProcess[str]:
            calls.append((command, timeout_s))
            return subprocess.CompletedProcess(command, 0, stdout="serial: 1234", stderr="")

        runner = UhdDiagnosticRunner("192.168.40.2", executor=fake_executor)
        result = runner.query("probe_device")

        self.assertEqual("success", result["status"])
        self.assertEqual(
            ["uhd_usrp_probe", "--args", "addr=192.168.40.2"],
            calls[0][0],
        )
        self.assertEqual("serial: 1234", result["results"][0]["stdout"])

    def test_summary_only_runs_allow_list(self) -> None:
        calls: list[list[str]] = []

        def fake_executor(command: list[str], timeout_s: float) -> subprocess.CompletedProcess[str]:
            calls.append(command)
            return subprocess.CompletedProcess(command, 0, stdout="ok", stderr="")

        runner = UhdDiagnosticRunner("192.168.40.2", executor=fake_executor)
        result = runner.query("summary")

        self.assertEqual("success", result["status"])
        self.assertEqual(
            ["uhd_config_info", "uhd_find_devices", "uhd_usrp_probe"],
            [command[0] for command in calls],
        )


if __name__ == "__main__":
    unittest.main()
