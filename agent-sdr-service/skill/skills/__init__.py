"""Load SDR skill guidance from Markdown definitions."""

from __future__ import annotations

from pathlib import Path

from skill.skill_spec import SkillSpec, SkillRegistry, load_skill_from_md

_HERE = Path(__file__).parent

_SKILL_FILES = [
    "stop.md",
    "physical_scan.md",
    "tone_loopback.md",
    "text_transmit.md",
    "device_diagnostics.md",
    "adaptive_transmit.md",
    "cognitive_select.md",
    "video_stream.md",
    "knowledge.md",
]


def _load_skills() -> dict[str, SkillSpec]:
    """Load all skills from .md files, keyed by skill name."""
    skill_map: dict[str, SkillSpec] = {}
    for md_name in _SKILL_FILES:
        md_path = _HERE / md_name
        skill = load_skill_from_md(md_path)
        skill_map[skill.name] = skill
    return skill_map


def build_all_skills() -> SkillRegistry:
    """Build prompt/allow-list skills; execution still belongs to ToolRegistry."""
    return SkillRegistry(list(_load_skills().values()))
