from __future__ import annotations

import asyncio
import inspect
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Callable

from pydantic import BaseModel, ConfigDict
from pydantic import ValidationError

if TYPE_CHECKING:
    from src.operation_idempotency import OperationClaim, RedisOperationCoordinator


class ToolInput(BaseModel):
    model_config = ConfigDict(extra="forbid")


@dataclass
class ToolSpec:
    name: str
    description: str
    schema_model: type[BaseModel]
    handler: Callable[[BaseModel], Any]
    category: str = "general"

    def as_openai_tool(self) -> dict[str, Any]:
        schema = self.schema_model.model_json_schema()
        schema.pop("title", None)
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": schema,
            },
        }


class ToolRegistry:
    def __init__(
        self,
        tool_specs: list[ToolSpec],
        operation_coordinator: "RedisOperationCoordinator | None" = None,
    ) -> None:
        self._tool_specs = list(tool_specs)
        self._tool_map = {tool.name: tool for tool in tool_specs}
        self._operation_coordinator = operation_coordinator
        if len(self._tool_map) != len(self._tool_specs):
            raise ValueError("工具名称不能重复")

    def all(self) -> list[ToolSpec]:
        return list(self._tool_specs)

    def as_openai_tools(
        self,
        *,
        names: set[str] | None = None,
        categories: set[str] | None = None,
    ) -> list[dict[str, Any]]:
        tools: list[dict[str, Any]] = []
        for tool in self._tool_specs:
            if names is not None and tool.name not in names:
                continue
            if categories is not None and tool.category not in categories:
                continue
            tools.append(tool.as_openai_tool())
        return tools

    def has_tool(self, name: str) -> bool:
        return name in self._tool_map

    def get_tool(self, name: str) -> ToolSpec | None:
        return self._tool_map.get(name)

    async def execute(
        self,
        name: str,
        payload: dict[str, Any],
        *,
        operation_id: str | None = None,
        allowed_tools: set[str] | None = None,
    ) -> dict[str, Any]:
        """Validate once and execute through the shared idempotency boundary."""
        if allowed_tools is not None and name not in allowed_tools:
            return {
                "status": "error",
                "errorType": "TOOL_NOT_ALLOWED",
                "tool": name,
                "allowedTools": sorted(allowed_tools),
                "retryable": False,
                "message": "当前 Skill 不允许调用该工具。",
            }
        tool = self._tool_map.get(name)
        if tool is None:
            return {"status": "error", "error": f"未知技能: {name}"}
        try:
            validated = tool.schema_model.model_validate(payload)
        except ValidationError as exc:
            details = [
                {
                    "type": item.get("type"),
                    "loc": list(item.get("loc", ())),
                    "msg": item.get("msg"),
                }
                for item in exc.errors()
            ]
            return {
                "status": "error",
                "error": f"技能 {name} 参数校验失败。",
                "details": details,
            }

        claim: "OperationClaim | None" = None
        validated_arguments = validated.model_dump(mode="json")
        if operation_id is not None:
            if self._operation_coordinator is None:
                return {
                    "status": "error",
                    "errorType": "IDEMPOTENCY_NOT_CONFIGURED",
                    "operationId": operation_id,
                    "operationState": "UNKNOWN",
                    "retryable": False,
                    "message": "硬件幂等协调层未配置，已拒绝执行。",
                }
            try:
                claim = await self._operation_coordinator.begin(
                    operation_id, name, validated_arguments
                )
            except ValueError as exc:
                return {
                    "status": "error",
                    "errorType": "INVALID_OPERATION_ID",
                    "operationId": operation_id,
                    "operationState": "REJECTED",
                    "retryable": False,
                    "message": str(exc),
                }
            except Exception as exc:
                return {
                    "status": "error",
                    "errorType": "IDEMPOTENCY_STORE_UNAVAILABLE",
                    "operationId": operation_id,
                    "operationState": "UNKNOWN",
                    "retryable": True,
                    "message": f"Redis 幂等状态不可用，已拒绝执行硬件工具: {exc}",
                }
            if not claim.should_execute:
                return claim.response or {
                    "status": "error",
                    "operationId": operation_id,
                    "operationState": claim.state,
                }

        try:
            if inspect.iscoroutinefunction(tool.handler):
                result = await tool.handler(validated)
            else:
                result = await asyncio.to_thread(tool.handler, validated)
                if inspect.isawaitable(result):
                    result = await result
            if isinstance(result, dict):
                normalized = result
            else:
                normalized = {"status": "success", "result": result}
        except Exception as exc:  # pragma: no cover - guardrail for tool handlers
            normalized = {
                "status": "error",
                "error": f"技能 {name} 执行失败: {exc}",
            }

        if claim is None or self._operation_coordinator is None:
            return normalized

        state = "SUCCESS" if normalized.get("status") == "success" else "FAILED"
        response = dict(normalized)
        response["operationId"] = operation_id
        response["operationState"] = state
        response["idempotentReplay"] = False
        try:
            stored = await self._operation_coordinator.complete(claim, state, response)
            if stored:
                return response
            return await self._operation_coordinator.get(operation_id)
        except Exception as exc:
            return {
                "status": "error",
                "errorType": "OPERATION_STATE_PERSIST_FAILED",
                "operationId": operation_id,
                "operationState": "UNKNOWN",
                "retryable": False,
                "message": (
                    "工具可能已经执行，但幂等结果写入失败；"
                    f"禁止自动重试，请先查询设备状态: {exc}"
                ),
            }
