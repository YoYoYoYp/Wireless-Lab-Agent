from __future__ import annotations

import asyncio
import os
import time
import unittest
import uuid

from pydantic import Field
from redis.asyncio import Redis

from src.operation_idempotency import RedisOperationCoordinator
from tools import ToolInput, ToolRegistry, ToolSpec


class _FakeRedis:
    def __init__(self) -> None:
        self.values: dict[str, str] = {}
        self.lock = asyncio.Lock()

    async def eval(self, script: str, num_keys: int, *args):
        keys = list(args[:num_keys])
        argv = list(args[num_keys:])
        async with self.lock:
            if script == RedisOperationCoordinator._CLAIM_SCRIPT:
                current = self.values.get(keys[0])
                if current is not None:
                    return [0, current]
                self.values[keys[0]] = str(argv[0])
                self.values[keys[1]] = str(argv[2])
                return [1, str(argv[0])]
            if script == RedisOperationCoordinator._COMPLETE_SCRIPT:
                if (
                    self.values.get(keys[0]) == argv[0]
                    and self.values.get(keys[1]) == argv[1]
                ):
                    self.values[keys[0]] = str(argv[2])
                    self.values.pop(keys[1], None)
                    return 1
                return 0
            if script == RedisOperationCoordinator._MARK_STALE_SCRIPT:
                current = self.values.get(keys[0])
                if current is None:
                    return [0, ""]
                if keys[1] in self.values:
                    return [0, current]
                if current == argv[0]:
                    self.values[keys[0]] = str(argv[1])
                    return [1, str(argv[1])]
                return [0, self.values.get(keys[0], "")]
            raise AssertionError("unexpected Lua script")

    async def get(self, key: str):
        return self.values.get(key)

    async def exists(self, key: str) -> int:
        return int(key in self.values)

    async def aclose(self) -> None:
        return None


class _Payload(ToolInput):
    value: int = Field(ge=0)


class OperationIdempotencyTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.redis = _FakeRedis()
        self.coordinator = RedisOperationCoordinator(
            self.redis, record_ttl_seconds=3600, lease_seconds=60
        )
        self.execution_count = 0

        def handler(payload: _Payload):
            self.execution_count += 1
            time.sleep(0.05)
            return {"status": "success", "value": payload.value}

        self.registry = ToolRegistry(
            [
                ToolSpec(
                    name="hardware_action",
                    description="test action",
                    schema_model=_Payload,
                    handler=handler,
                    category="hardware",
                )
            ],
            operation_coordinator=self.coordinator,
        )

    async def test_repeated_operation_replays_result_without_second_execution(self):
        first = await self.registry.execute(
            "hardware_action", {"value": 7}, operation_id="op-replay"
        )
        second = await self.registry.execute(
            "hardware_action", {"value": 7}, operation_id="op-replay"
        )

        self.assertEqual(1, self.execution_count)
        self.assertEqual("SUCCESS", first["operationState"])
        self.assertFalse(first["idempotentReplay"])
        self.assertEqual("SUCCESS", second["operationState"])
        self.assertTrue(second["idempotentReplay"])

    async def test_concurrent_duplicate_observes_running_and_executes_once(self):
        first, second = await asyncio.gather(
            self.registry.execute(
                "hardware_action", {"value": 8}, operation_id="op-concurrent"
            ),
            self.registry.execute(
                "hardware_action", {"value": 8}, operation_id="op-concurrent"
            ),
        )

        self.assertEqual(1, self.execution_count)
        states = {first["operationState"], second["operationState"]}
        self.assertEqual({"RUNNING", "SUCCESS"}, states)

    async def test_same_operation_id_with_different_arguments_is_rejected(self):
        await self.registry.execute(
            "hardware_action", {"value": 1}, operation_id="op-conflict"
        )
        conflict = await self.registry.execute(
            "hardware_action", {"value": 2}, operation_id="op-conflict"
        )

        self.assertEqual(1, self.execution_count)
        self.assertEqual("OPERATION_ID_CONFLICT", conflict["errorType"])

    async def test_expired_running_lease_becomes_unknown_instead_of_reexecuting(self):
        claim = await self.coordinator.begin(
            "op-stale", "hardware_action", {"value": 3}
        )
        self.redis.values.pop("sdr:operation:op-stale:lease")

        duplicate = await self.coordinator.begin(
            "op-stale", "hardware_action", {"value": 3}
        )

        self.assertTrue(claim.should_execute)
        self.assertFalse(duplicate.should_execute)
        self.assertEqual("UNKNOWN", duplicate.state)
        self.assertEqual("OPERATION_STATE_UNKNOWN", duplicate.response["errorType"])


@unittest.skipUnless(
    os.getenv("RUN_OPERATION_REDIS_INTEGRATION_TESTS", "false").lower() == "true",
    "set RUN_OPERATION_REDIS_INTEGRATION_TESTS=true to use a real Redis",
)
class RedisOperationCoordinatorIntegrationTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.operation_id = f"integration-{uuid.uuid4()}"
        self.redis = Redis.from_url(
            os.getenv("REDIS_URL", "redis://127.0.0.1:6379/0"),
            decode_responses=True,
        )
        try:
            await self.redis.ping()
        except Exception as exc:
            self.skipTest(f"Redis unavailable: {exc}")
        self.coordinator = RedisOperationCoordinator(
            self.redis, record_ttl_seconds=60, lease_seconds=10
        )

    async def asyncTearDown(self) -> None:
        if hasattr(self, "redis"):
            await self.redis.delete(
                f"sdr:operation:{self.operation_id}",
                f"sdr:operation:{self.operation_id}:lease",
            )
            await self.redis.aclose()

    async def test_real_redis_claim_complete_and_replay(self):
        claim = await self.coordinator.begin(
            self.operation_id, "hardware_action", {"value": 9}
        )
        stored = await self.coordinator.complete(
            claim,
            "SUCCESS",
            {
                "status": "success",
                "operationId": self.operation_id,
                "operationState": "SUCCESS",
            },
        )
        replay = await self.coordinator.begin(
            self.operation_id, "hardware_action", {"value": 9}
        )

        self.assertTrue(claim.should_execute)
        self.assertTrue(stored)
        self.assertFalse(replay.should_execute)
        self.assertEqual("SUCCESS", replay.state)
        self.assertTrue(replay.response["idempotentReplay"])


if __name__ == "__main__":
    unittest.main()
