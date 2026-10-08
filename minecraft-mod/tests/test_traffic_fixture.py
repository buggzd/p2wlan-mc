"""Fixture lifecycle unit checks; these do not prove P2WLAN traffic."""
import asyncio
from pathlib import Path
import sys
import unittest
from unittest.mock import AsyncMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from control_smoke import FixtureError, environment
import traffic_fixture as fixture


class Probe:
    def __init__(self, ready_after=0, failure=None):
        self.ready_after, self.failure = ready_after, failure
        self.observations, self.pings = 0, 0

    async def request(self, operation, arguments=None, error=None):
        if operation == "ping":
            self.pings += 1
            return {"alive": True}
        if operation != "peer_status":
            raise AssertionError("readiness must use read-only operations")
        if self.failure:
            raise FixtureError(self.failure, "peer_status")
        self.observations += 1
        return arguments | {"view": {"online": True, "path": "direct",
            "direct_budget_ready": self.observations > self.ready_after}}


class TrafficFixtureTests(unittest.IsolatedAsyncioTestCase):
    def test_fixture_environment_does_not_inherit_credentials_or_proxies(self):
        with patch.dict("os.environ", {"JWT_SECRET": "fixture-secret", "CONTROL_BIND": "fixture-bind",
                "http_proxy": "fixture-proxy", "ALL_PROXY": "fixture-proxy", "PATH": "fixture-path"}, clear=True):
            self.assertEqual(environment(), {"PATH": "fixture-path"})

    async def test_packet_gateway_ready_does_not_replace_business_budget_readiness(self):
        agents = [Probe(1), Probe()]
        await fixture.wait_peer_paths(agents, ["r0", "r1"], ["p1", "p0"], FixtureError)
        self.assertEqual([agent.observations for agent in agents], [2, 2])
        self.assertEqual([agent.pings for agent in agents], [2, 2])

    async def test_missing_peer_has_total_deadline_without_registration_or_replay(self):
        agents = [Probe(failure="UNKNOWN_PEER"), Probe()]
        with patch.object(fixture, "PATH_TIMEOUT", 0.02):
            with self.assertRaises(FixtureError) as caught:
                await fixture.wait_peer_paths(agents, ["r0", "r1"], ["p1", "p0"], FixtureError)
        self.assertEqual(caught.exception.code, "PEER_PATH_DEADLINE")
        self.assertEqual([agent.pings for agent in agents], [1, 1])

    async def test_stale_runtime_is_terminal_not_retried_as_startup(self):
        agents = [Probe(failure="RUNTIME_CHANGED"), Probe()]
        with self.assertRaises(FixtureError) as caught:
            await fixture.wait_peer_paths(agents, ["r0", "r1"], ["p1", "p0"], FixtureError)
        self.assertEqual(caught.exception.code, "RUNTIME_CHANGED")
        self.assertEqual(agents[0].pings, 1)
        self.assertEqual(agents[1].pings, 0)

    async def test_foreign_peer_view_cannot_count_as_ready(self):
        class Foreign(Probe):
            async def request(self, operation, arguments=None, error=None):
                reply = await super().request(operation, arguments, error)
                return reply | {"runtime": "foreign-runtime"} if operation == "peer_status" else reply
        with self.assertRaises(FixtureError) as caught:
            await fixture.wait_peer_paths([Foreign(), Probe()], ["r0", "r1"], ["p1", "p0"], FixtureError)
        self.assertEqual(caught.exception.code, "PEER_VIEW_IDENTITY")

    async def test_heartbeat_failure_cannot_skip_target_listener_cleanup(self):
        servers, heartbeat_tasks = [], []
        original_start = asyncio.start_server
        armed = False

        async def start_server(*args, **kwargs):
            server = await original_start(*args, **kwargs)
            servers.append(server)
            return server

        class Agent(Probe):
            async def request(self, operation, arguments=None, error=None):
                nonlocal armed
                if operation == "ping" and armed:
                    heartbeat_tasks.append(asyncio.current_task())
                    raise FixtureError("FIXTURE_HEARTBEAT")
                if operation in {"ping", "peer_status"}:
                    return await super().request(operation, arguments, error)
                if operation == "status":
                    return {"peer_id": "client-peer", "admission_drops": {}}
                if operation == "host":
                    return {"registration": "host", "port": 1024, "service": "00" * 32}
                if operation == "close_registration":
                    return None
                if operation == "discover":
                    return arguments | {"services": [] if arguments["runtime"] == "r0" else [{"port": 1024, "service": "00" * 32}]}
                if operation == "proxy":
                    armed = True
                    return {"registration": "proxy", "port": 12345, "key": "00" * 32}
                raise AssertionError(operation)

        with patch.object(fixture.asyncio, "start_server", start_server), patch.object(
                fixture.asyncio, "open_connection", AsyncMock(side_effect=ConnectionError)):
            with self.assertRaises(FixtureError) as caught:
                await fixture.transfer([Agent(), Agent()], ["r0", "r1"], "host-peer", FixtureError)
        self.assertEqual(caught.exception.code, "FIXTURE_HEARTBEAT")
        self.assertEqual(len(servers), 1)
        self.assertFalse(servers[0].is_serving())
        self.assertEqual(len(heartbeat_tasks), 1)
        self.assertTrue(heartbeat_tasks[0].done())


if __name__ == "__main__":
    unittest.main()
