from __future__ import annotations

import unittest

from pydantic import ValidationError

from skill.skills import build_all_skills
from tools import ToolInput, ToolRegistry, ToolSpec
from tools.physical_tools import ScanRequest, ToneLoopbackRequest
from tools.transmit_tools import FskSendRequest, AutoOptimalTransmitRequest


REGISTERED_TOOL_NAMES = {
    "perform_physical_scan",
    "tone_loopback_visualize",
    "stop_hardware_task",
    "text_fsk_send_and_receive",
    "adaptive_modulation_transmit",
    "auto_optimal_transmit",
    "search_sdr_knowledge",
    "query_usrp_device_parameters",
    "start_video_stream",
    "stop_video_stream",
    "video_stream_status",
}


class _EmptyInput(ToolInput):
    pass


class SkillAllowListTest(unittest.IsolatedAsyncioTestCase):
    def test_every_skill_declares_only_registered_tools(self):
        skills = build_all_skills().all()

        self.assertGreater(len(skills), 0)
        for skill in skills:
            self.assertTrue(skill.allowed_tools, skill.name)
            self.assertIn(skill.target_tool, skill.allowed_tools)
            self.assertTrue(
                set(skill.allowed_tools).issubset(REGISTERED_TOOL_NAMES),
                f"{skill.name}: {skill.allowed_tools}",
            )

    async def test_registry_rejects_tool_outside_skill_allow_list(self):
        calls: list[str] = []
        registry = ToolRegistry(
            [
                ToolSpec(
                    name="safe_tool",
                    description="safe",
                    schema_model=_EmptyInput,
                    handler=lambda payload: calls.append("safe") or {"status": "success"},
                ),
                ToolSpec(
                    name="dangerous_tool",
                    description="dangerous",
                    schema_model=_EmptyInput,
                    handler=lambda payload: calls.append("dangerous") or {"status": "success"},
                ),
            ]
        )

        exposed = registry.as_openai_tools(names={"safe_tool"})
        rejected = await registry.execute(
            "dangerous_tool", {}, allowed_tools={"safe_tool"}
        )

        self.assertEqual(["safe_tool"], [item["function"]["name"] for item in exposed])
        self.assertEqual("TOOL_NOT_ALLOWED", rejected["errorType"])
        self.assertEqual([], calls)


class RadioParameterPolicyTest(unittest.TestCase):
    def test_modulation_is_normalized_and_limited(self):
        request = FskSendRequest.model_validate(
            {"text": "NJUPT", "modulation_scheme": "qpsk"}
        )
        self.assertEqual("QPSK", request.modulation_scheme)

        with self.assertRaises(ValidationError):
            FskSendRequest.model_validate(
                {"text": "NJUPT", "modulation_scheme": "64-QAM"}
            )

    def test_transmit_frequency_gain_and_amplitude_are_limited(self):
        invalid_payloads = [
            {"text": "NJUPT", "center_freq_hz": 900e6},
            {"text": "NJUPT", "tx_gain": 40},
            {"text": "NJUPT", "rx_gain": 40},
            {"text": "NJUPT", "amp": 1.1},
        ]

        for payload in invalid_payloads:
            with self.subTest(payload=payload), self.assertRaises(ValidationError):
                FskSendRequest.model_validate(payload)

    def test_baseband_rates_must_stay_inside_nyquist_limit(self):
        with self.assertRaises(ValidationError):
            FskSendRequest.model_validate(
                {
                    "text": "NJUPT",
                    "samp_rate": 1e6,
                    "sym_rate": 300e3,
                    "f_dev": 250e3,
                }
            )

    def test_scan_and_tone_parameters_are_limited(self):
        with self.assertRaises(ValidationError):
            ScanRequest.model_validate({"center_freq_hz": 6.1e9})
        with self.assertRaises(ValidationError):
            ScanRequest.model_validate({"chan": 2})
        with self.assertRaises(ValidationError):
            ToneLoopbackRequest.model_validate(
                {"samp_rate": 1e6, "tone_freq_hz": 600e3}
            )
        with self.assertRaises(ValidationError):
            ScanRequest.model_validate(
                {"bandwidth_hz": 40e6, "duration_s": 10}
            )

    def test_transmit_array_size_is_bounded_by_samples_per_symbol(self):
        with self.assertRaises(ValidationError):
            FskSendRequest.model_validate(
                {"text": "NJUPT", "samp_rate": 40e6, "sym_rate": 1e3}
            )

    def test_cognitive_search_window_must_fit_device_band(self):
        with self.assertRaises(ValidationError):
            AutoOptimalTransmitRequest.model_validate(
                {"center_freq_hz": 1.2e9, "search_span_hz": 30e6}
            )


if __name__ == "__main__":
    unittest.main()
