"""Skill registry factory — loads all .md skills.

This is a thin wrapper around skill/skills/__init__.py:build_all_skills().
In the new architecture, skills are used for:
    1. Regex-based intent matching (before LLM call)
    2. Prompt injection (system prompt enrichment)

The actual tool execution goes through the shared ToolRegistry, not through
skill handlers or the MCP transport.
"""

from __future__ import annotations

from skill.skills import build_all_skills
from skill.skill_spec import SkillRegistry
from tools import ToolRegistry


def create_skill_registry(tool_registry: ToolRegistry) -> SkillRegistry:
    """Build the skill registry from .md files.

    In the Claude Code architecture, skills only provide:
    - Regex matching (trigger_patterns, trigger_keywords)
    - System prompt injection (the .md body)

    Tool execution goes through the shared ToolRegistry.  Fail fast when a
    skill points at a tool that was not registered for this deployment.
    """
    registry = build_all_skills()
    missing = sorted({
        skill.target_tool
        for skill in registry.all()
        if not tool_registry.has_tool(skill.target_tool)
    })
    if missing:
        raise ValueError(f"Skill 引用了未注册工具: {', '.join(missing)}")
    return registry
