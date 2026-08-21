"""FastAPI + SSE entry point — serves the web UI and MCP SSE endpoint.

Usage:
    uvicorn src.server:app --host 127.0.0.1 --port 8000

Architecture:
    - /api/chat        — Agent Loop (non-streaming)
    - /api/chat/stream — Agent Loop (SSE streaming)
    - /api/health      — Health check
    - /api/hardware_status — Hardware snapshot
    - /mcp             — MCP SSE endpoint (for external agents)
    - /                — Static web UI
"""

from __future__ import annotations

import json
import socket
import uuid
from typing import Any, AsyncIterator

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from src.agent.loop import _agent_id_for_tool, _post_console_event
from src.config import settings
from src.mcp import create_sdr_mcp
from src.operation_idempotency import RedisOperationCoordinator

# ═══════════════════════════════════════════════════════════════════════════
# Bootstrap — same as old main.py
# ═══════════════════════════════════════════════════════════════════════════

from core.memory import ConversationMemory
from core.rag import LocalKnowledgeBase
from hardware.sdr_controller import SDRController
from hardware.uhd_diagnostics import UhdDiagnosticRunner
from hardware.video_stream_controller import VideoStreamController
from tools.registry import create_tool_registry

memory = ConversationMemory(max_messages=settings.session_history_limit)
knowledge_base = LocalKnowledgeBase(
    app_id=settings.bailian_app_id,
    api_key=settings.bailian_api_key,
)
controller = SDRController(settings.usrp_ip)
diagnostic_runner = UhdDiagnosticRunner(settings.usrp_ip)
video_controller = VideoStreamController(
    release_cb=controller.disconnect_usrp,
    reconnect_cb=controller.reconnect_usrp,
)
operation_coordinator = RedisOperationCoordinator.from_url(
    settings.redis_url,
    record_ttl_seconds=settings.operation_record_ttl_seconds,
    lease_seconds=settings.operation_lease_seconds,
)

# Build the single validated registry used by both local Agent Loop and MCP.
tool_registry = create_tool_registry(
    controller=controller,
    knowledge_base=knowledge_base,
    video_controller=video_controller,
    diagnostic_runner=diagnostic_runner,
    operation_coordinator=operation_coordinator,
)
mcp_server = create_sdr_mcp(
    tool_registry=tool_registry,
    controller=controller,
    knowledge_base=knowledge_base,
)

# Create skill registry
from src.skills.registry import create_skill_registry

skill_registry = create_skill_registry(tool_registry)

# Create agent loop
from openai import OpenAI

from src.agent.loop import AgentLoop

llm_client = OpenAI(
    api_key=settings.api_key,
    base_url=settings.ollama_base_url,
)

agent_loop = AgentLoop(
    llm_client=llm_client,
    tool_registry=tool_registry,
    skill_registry=skill_registry,
    memory=memory,
    hardware_busy=controller.has_background_task,
)

# ═══════════════════════════════════════════════════════════════════════════
# FastAPI application
# ═══════════════════════════════════════════════════════════════════════════

app = FastAPI(title=settings.app_name)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ── Pydantic models ──────────────────────────────────────────────────────


class ChatRequest(BaseModel):
    instruction: str = Field(min_length=1)
    session_id: str = Field(min_length=1)
    mode: str = "think"
    temperature: float | None = Field(default=None, ge=0.0, le=2.0)
    operation_id: str | None = Field(
        default=None,
        min_length=1,
        max_length=128,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]*$",
    )


class ToolExecutionRequest(BaseModel):
    operation_id: str = Field(
        min_length=1,
        max_length=128,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]*$",
    )
    tool_name: str = Field(min_length=1)
    arguments: dict[str, Any] = Field(default_factory=dict)


class OperationRequest(BaseModel):
    operation_id: str | None = Field(
        default=None,
        min_length=1,
        max_length=128,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]*$",
    )


class ResetSessionRequest(BaseModel):
    session_id: str = Field(min_length=1)


# ── Helpers ──────────────────────────────────────────────────────────────


def get_host_ip() -> str:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect((settings.usrp_ip, 80))
        return sock.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        sock.close()


# ── REST endpoints ────────────────────────────────────────────────────────


@app.get("/api/health")
def health_check() -> dict:
    return {
        "status": "ok",
        "app": settings.app_name,
        "models": settings.model_map,
        "knowledge_documents": knowledge_base.document_count,
        "uhd_available": controller.driver_available,
    }


@app.get("/api/hardware_status")
def get_hardware_status() -> dict:
    return {
        "server_ip": get_host_ip(),
        "usrp_01_ip": settings.usrp_ip,
        "usrp_02_ip": "Offline",
        "diagnostics": controller.get_hardware_snapshot(),
        "visualization_active": controller.get_visualization_snapshot().get(
            "active", False
        ),
        "video_stream": video_controller.get_state(),
        "knowledge_documents": knowledge_base.document_count,
    }


@app.get("/api/visualization")
def get_visualization_state() -> dict:
    return controller.get_visualization_snapshot()


@app.post("/api/hardware/stop")
async def stop_hardware_task(payload: OperationRequest | None = None) -> dict:
    base_operation_id = (
        payload.operation_id if payload and payload.operation_id else f"http-stop-{uuid.uuid4()}"
    )
    hw_result = await tool_registry.execute(
        "stop_hardware_task", {}, operation_id=f"{base_operation_id}:hardware"
    )
    video_result = (
        await tool_registry.execute(
            "stop_video_stream", {}, operation_id=f"{base_operation_id}:video"
        )
        if tool_registry.has_tool("stop_video_stream")
        else {"status": "success", "message": "视频工具未启用。"}
    )
    # Flush any pending console completion — the USRP is now idle
    agent_loop._flush_pending_completion()
    return {"hardware": hw_result, "video_stream": video_result}


@app.post("/api/tools/execute")
async def execute_tool(payload: ToolExecutionRequest) -> dict:
    """HTTP fallback and MCP share this ToolRegistry idempotency boundary."""
    return await tool_registry.execute(
        payload.tool_name,
        payload.arguments,
        operation_id=payload.operation_id,
    )


@app.get("/api/operations/{operation_id}")
async def get_operation(operation_id: str) -> dict:
    try:
        return await operation_coordinator.get(operation_id)
    except ValueError as exc:
        return {
            "status": "error",
            "errorType": "INVALID_OPERATION_ID",
            "operationId": operation_id,
            "message": str(exc),
        }
    except Exception as exc:
        return {
            "status": "error",
            "errorType": "IDEMPOTENCY_STORE_UNAVAILABLE",
            "operationId": operation_id,
            "message": str(exc),
        }


@app.post("/api/session/reset")
def reset_session(payload: ResetSessionRequest) -> dict:
    memory.reset(payload.session_id)
    return {"status": "success", "message": "Session context cleared."}


# ── Chat endpoints ───────────────────────────────────────────────────────


@app.post("/api/chat")
async def chat(payload: ChatRequest) -> dict:
    """Non-streaming chat — collects all events and returns final result."""
    reply_parts: list[str] = []
    tool_logs: list[str] = []
    final_payload: dict[str, Any] = {}
    run_id = str(uuid.uuid4())
    operation_id = payload.operation_id or f"http-chat-{uuid.uuid4()}"

    # Notify console synchronously — must arrive before run_stream starts
    await _post_console_event(
        agent_id="usrp",
        run_id=run_id,
        session_id=payload.session_id,
        phase="started",
        task=payload.instruction,
        tool_name="",
    )

    try:
        async for event in agent_loop.run_stream(
            instruction=payload.instruction,
            session_id=payload.session_id,
            mode=payload.mode,
            temperature=payload.temperature,
            run_id=run_id,
            operation_id=operation_id,
        ):
            t = event.get("type")
            if t == "delta" and event.get("text"):
                reply_parts.append(str(event["text"]))
            elif t == "tool_log" and event.get("line"):
                tool_logs.append(str(event["line"]))
            elif t == "done":
                final_payload = event
            elif t == "error":
                return {
                    "status": "error",
                    "message": event.get("message", "Unknown error"),
                    "reply": "".join(reply_parts),
                    "hardware_logs": tool_logs,
                    "updated_history": memory.get_history(payload.session_id),
                    "active_mode": agent_loop.normalize_mode(payload.mode),
                    "active_model": agent_loop.resolve_model(payload.mode),
                    "active_temperature": agent_loop.resolve_temperature(
                        payload.mode, payload.temperature
                    ),
                    "operationId": operation_id,
                }
    except Exception as exc:
        return {
            "status": "error",
            "message": str(exc),
            "reply": "".join(reply_parts),
            "hardware_logs": tool_logs,
            "updated_history": memory.get_history(payload.session_id),
            "active_mode": agent_loop.normalize_mode(payload.mode),
            "active_model": agent_loop.resolve_model(payload.mode),
            "active_temperature": agent_loop.resolve_temperature(
                payload.mode, payload.temperature
            ),
            "operationId": operation_id,
        }

    return {
        "status": final_payload.get("status", "success"),
        "reply": final_payload.get("reply", "".join(reply_parts)),
        "hardware_logs": final_payload.get("hardware_logs", tool_logs),
        "updated_history": final_payload.get(
            "updated_history", memory.get_history(payload.session_id)
        ),
        "active_mode": final_payload.get(
            "active_mode", agent_loop.normalize_mode(payload.mode)
        ),
        "active_model": final_payload.get(
            "active_model", agent_loop.resolve_model(payload.mode)
        ),
        "active_temperature": final_payload.get(
            "active_temperature",
            agent_loop.resolve_temperature(payload.mode, payload.temperature),
        ),
        "timing": final_payload.get("timing"),
        "operationId": operation_id,
    }


@app.post("/api/chat/stream")
async def chat_stream(payload: ChatRequest) -> StreamingResponse:
    """SSE streaming endpoint — token deltas, tool logs, and final payload."""

    async def event_gen() -> AsyncIterator[str]:
        import asyncio as _asyncio

        run_id = str(uuid.uuid4())
        operation_id = payload.operation_id or f"http-stream-{uuid.uuid4()}"
        # Notify console synchronously — must arrive before run_stream starts
        await _post_console_event(
            agent_id="usrp",
            run_id=run_id,
            session_id=payload.session_id,
            phase="started",
            task=payload.instruction,
            tool_name="",
        )

        async for event in agent_loop.run_stream(
            instruction=payload.instruction,
            session_id=payload.session_id,
            mode=payload.mode,
            temperature=payload.temperature,
            run_id=run_id,
            operation_id=operation_id,
        ):
            yield f"data: {json.dumps(event, ensure_ascii=False)}\n\n"
            # Force the event loop to flush after each delta so the browser
            # renders tokens incrementally instead of receiving one big burst.
            if event.get("type") == "delta":
                await _asyncio.sleep(0)

    return StreamingResponse(
        event_gen(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@app.on_event("shutdown")
async def close_operation_store() -> None:
    await operation_coordinator.close()


# ── Mount MCP + static ────────────────────────────────────────────────────

# MCP SSE endpoint — external agents connect here
app.mount("/mcp", mcp_server.sse_app())

# Static web UI
app.mount(
    "/", StaticFiles(directory=str(settings.static_dir), html=True), name="web_root"
)
