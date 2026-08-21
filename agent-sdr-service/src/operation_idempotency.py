"""Redis-backed idempotency state shared by MCP and HTTP tool execution."""

from __future__ import annotations

import hashlib
import inspect
import json
import re
import time
import uuid
from dataclasses import dataclass
from typing import Any

from redis.asyncio import Redis


OPERATION_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")


@dataclass(frozen=True)
class OperationClaim:
    operation_id: str
    should_execute: bool
    state: str
    owner_token: str | None = None
    running_record: str | None = None
    response: dict[str, Any] | None = None


class RedisOperationCoordinator:
    """Claim an operation once and persist its observable execution state.

    The operation record outlives the short execution lease. If a process dies
    while the record is RUNNING, a later observer converts it to UNKNOWN rather
    than executing the hardware action again.
    """

    _CLAIM_SCRIPT = """
        local current = redis.call('get', KEYS[1])
        if current then
            return {0, current}
        end
        redis.call('set', KEYS[1], ARGV[1], 'EX', ARGV[2])
        redis.call('set', KEYS[2], ARGV[3], 'EX', ARGV[4])
        return {1, ARGV[1]}
    """

    _COMPLETE_SCRIPT = """
        if redis.call('get', KEYS[1]) == ARGV[1]
           and redis.call('get', KEYS[2]) == ARGV[2] then
            redis.call('set', KEYS[1], ARGV[3], 'EX', ARGV[4])
            redis.call('del', KEYS[2])
            return 1
        end
        return 0
    """

    _MARK_STALE_SCRIPT = """
        local current = redis.call('get', KEYS[1])
        if not current then
            return {0, ''}
        end
        if redis.call('exists', KEYS[2]) == 1 then
            return {0, current}
        end
        if current == ARGV[1] then
            redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[3])
            return {1, ARGV[2]}
        end
        return {0, redis.call('get', KEYS[1]) or ''}
    """

    def __init__(
        self,
        redis_client: Redis,
        *,
        key_prefix: str = "sdr:operation:",
        record_ttl_seconds: int = 7 * 24 * 60 * 60,
        lease_seconds: int = 5 * 60,
    ) -> None:
        if record_ttl_seconds <= 0 or lease_seconds <= 0:
            raise ValueError("operation TTL and lease must be positive")
        if lease_seconds >= record_ttl_seconds:
            raise ValueError("operation lease must be shorter than record TTL")
        self._redis = redis_client
        self._key_prefix = key_prefix
        self._record_ttl_seconds = record_ttl_seconds
        self._lease_seconds = lease_seconds

    @classmethod
    def from_url(
        cls,
        redis_url: str,
        *,
        key_prefix: str = "sdr:operation:",
        record_ttl_seconds: int = 7 * 24 * 60 * 60,
        lease_seconds: int = 5 * 60,
    ) -> "RedisOperationCoordinator":
        client = Redis.from_url(redis_url, decode_responses=True)
        return cls(
            client,
            key_prefix=key_prefix,
            record_ttl_seconds=record_ttl_seconds,
            lease_seconds=lease_seconds,
        )

    async def begin(
        self,
        operation_id: str,
        tool_name: str,
        arguments: dict[str, Any],
    ) -> OperationClaim:
        self.validate_operation_id(operation_id)
        fingerprint = self._fingerprint(tool_name, arguments)
        owner_token = str(uuid.uuid4())
        now_ms = int(time.time() * 1000)
        record = {
            "operationId": operation_id,
            "tool": tool_name,
            "argumentsHash": fingerprint,
            "state": "RUNNING",
            "ownerToken": owner_token,
            "createdAtEpochMs": now_ms,
            "updatedAtEpochMs": now_ms,
        }
        running_raw = self._dump(record)
        result = await self._redis.eval(
            self._CLAIM_SCRIPT,
            2,
            self._record_key(operation_id),
            self._lease_key(operation_id),
            running_raw,
            self._record_ttl_seconds,
            owner_token,
            self._lease_seconds,
        )
        claimed = bool(int(result[0]))
        current_raw = str(result[1])
        if claimed:
            return OperationClaim(
                operation_id=operation_id,
                should_execute=True,
                state="RUNNING",
                owner_token=owner_token,
                running_record=running_raw,
            )
        return await self._decision_from_existing(
            operation_id, tool_name, fingerprint, current_raw
        )

    async def complete(
        self,
        claim: OperationClaim,
        state: str,
        result: dict[str, Any],
    ) -> bool:
        if not claim.should_execute or not claim.owner_token or not claim.running_record:
            return False
        current = json.loads(claim.running_record)
        current["state"] = state
        current["updatedAtEpochMs"] = int(time.time() * 1000)
        current["result"] = result
        current.pop("ownerToken", None)
        completed = await self._redis.eval(
            self._COMPLETE_SCRIPT,
            2,
            self._record_key(claim.operation_id),
            self._lease_key(claim.operation_id),
            claim.running_record,
            claim.owner_token,
            self._dump(current),
            self._record_ttl_seconds,
        )
        return bool(int(completed))

    async def get(self, operation_id: str) -> dict[str, Any]:
        self.validate_operation_id(operation_id)
        raw = await self._redis.get(self._record_key(operation_id))
        if not raw:
            return {
                "status": "not_found",
                "operationId": operation_id,
                "operationState": "NOT_FOUND",
            }
        record = json.loads(raw)
        if record.get("state") == "RUNNING":
            record = await self._mark_unknown_if_stale(operation_id, raw, record)
        return self._response_from_record(record, replay=True)

    async def close(self) -> None:
        close = getattr(self._redis, "aclose", None) or getattr(self._redis, "close")
        result = close()
        if inspect.isawaitable(result):
            await result

    @staticmethod
    def validate_operation_id(operation_id: str) -> None:
        if not OPERATION_ID_PATTERN.fullmatch(operation_id or ""):
            raise ValueError(
                "operation_id must be 1-128 characters using letters, digits, '.', '_', ':' or '-'"
            )

    async def _decision_from_existing(
        self,
        operation_id: str,
        tool_name: str,
        fingerprint: str,
        current_raw: str,
    ) -> OperationClaim:
        record = json.loads(current_raw)
        if record.get("tool") != tool_name or record.get("argumentsHash") != fingerprint:
            return OperationClaim(
                operation_id=operation_id,
                should_execute=False,
                state="CONFLICT",
                response={
                    "status": "error",
                    "errorType": "OPERATION_ID_CONFLICT",
                    "operationId": operation_id,
                    "operationState": "CONFLICT",
                    "retryable": False,
                    "message": "同一个 operationId 不能用于不同工具或不同参数。",
                },
            )
        if record.get("state") == "RUNNING":
            record = await self._mark_unknown_if_stale(
                operation_id, current_raw, record
            )
        return OperationClaim(
            operation_id=operation_id,
            should_execute=False,
            state=str(record.get("state", "UNKNOWN")),
            response=self._response_from_record(record, replay=True),
        )

    async def _mark_unknown_if_stale(
        self,
        operation_id: str,
        current_raw: str,
        record: dict[str, Any],
    ) -> dict[str, Any]:
        if await self._redis.exists(self._lease_key(operation_id)):
            return record
        unknown = dict(record)
        unknown["state"] = "UNKNOWN"
        unknown["updatedAtEpochMs"] = int(time.time() * 1000)
        unknown.pop("ownerToken", None)
        unknown["message"] = "执行租约已过期，无法确认硬件动作是否完成。"
        result = await self._redis.eval(
            self._MARK_STALE_SCRIPT,
            2,
            self._record_key(operation_id),
            self._lease_key(operation_id),
            current_raw,
            self._dump(unknown),
            self._record_ttl_seconds,
        )
        latest_raw = str(result[1])
        return json.loads(latest_raw) if latest_raw else unknown

    def _response_from_record(
        self, record: dict[str, Any], *, replay: bool
    ) -> dict[str, Any]:
        state = str(record.get("state", "UNKNOWN"))
        operation_id = str(record.get("operationId", ""))
        if state in {"SUCCESS", "FAILED"} and isinstance(record.get("result"), dict):
            response = dict(record["result"])
            response["operationId"] = operation_id
            response["operationState"] = state
            response["idempotentReplay"] = replay
            return response
        if state == "RUNNING":
            return {
                "status": "pending",
                "operationId": operation_id,
                "operationState": "RUNNING",
                "idempotentReplay": replay,
                "retryable": False,
                "message": "相同操作正在执行，本次请求不会重复调用硬件。",
            }
        return {
            "status": "error",
            "errorType": "OPERATION_STATE_UNKNOWN",
            "operationId": operation_id,
            "operationState": "UNKNOWN",
            "idempotentReplay": replay,
            "retryable": False,
            "message": record.get(
                "message", "无法确认硬件动作是否完成，请先查询设备状态。"
            ),
        }

    def _record_key(self, operation_id: str) -> str:
        return f"{self._key_prefix}{operation_id}"

    def _lease_key(self, operation_id: str) -> str:
        return f"{self._record_key(operation_id)}:lease"

    @staticmethod
    def _fingerprint(tool_name: str, arguments: dict[str, Any]) -> str:
        canonical = json.dumps(
            arguments, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        )
        return hashlib.sha256(f"{tool_name}\0{canonical}".encode("utf-8")).hexdigest()

    @staticmethod
    def _dump(value: dict[str, Any]) -> str:
        return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
