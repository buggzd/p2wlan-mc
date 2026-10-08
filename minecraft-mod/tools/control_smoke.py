#!/usr/bin/env python3
"""Isolated real-Control/Agent account and managed-runtime integration fixture."""
import argparse
import asyncio
import json
import os
from pathlib import Path
import secrets
import socket
import struct
import tempfile
import time
import urllib.error
import urllib.request

PROJECT = Path(__file__).resolve().parents[1]
FRAME_LIMIT = 64 * 1024


class FixtureError(Exception):
    def __init__(self, code, operation=None):
        self.code, self.operation = code, operation
        super().__init__(f"{operation}:{code}" if operation else code)


def environment():
    # Do not inherit a developer's Control/Relay credentials or deployment.
    return {key: value for key, value in os.environ.items()
            if not key.upper().startswith(("CONTROL_", "RELAY_", "P2WLAN_"))
            and key.upper() not in ("JWT_SECRET", "DB_PATH", "PORT", "LOG_UPLOAD_DIR",
                                    "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY")}


def available_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def healthy(port):
    # Ignore ambient HTTP proxies even for the isolated loopback fixture.
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with client.open(f"http://127.0.0.1:{port}/health", timeout=0.5) as response:
            return response.status == 200 and response.read(16) == b"ok"
    except (OSError, urllib.error.URLError):
        return False


class Agent:
    def __init__(self, process, owner):
        self.process, self.owner = process, owner
        self.request_id, self.revision = 0, 0
        self.lock = asyncio.Lock()

    @classmethod
    async def launch(cls, binary, directory):
        process = await asyncio.create_subprocess_exec(
            str(binary), "--profile-dir", str(directory),
            stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL, env=environment())
        try:
            value = await cls.read_frame(process)
            owner = value.get("owner")
            if value.get("event") != "hello" or value.get("version") != 1 or not isinstance(owner, str) or len(owner) != 32:
                raise FixtureError("AGENT_HELLO")
            return cls(process, owner)
        except BaseException:
            await terminate(process)
            raise

    @staticmethod
    async def read_frame(process):
        async def read():
            size, = struct.unpack(">I", await process.stdout.readexactly(4))
            if not 0 < size <= FRAME_LIMIT:
                raise FixtureError("FRAME_SIZE")
            return json.loads(await process.stdout.readexactly(size))
        return await asyncio.wait_for(read(), 4)

    async def request(self, operation, arguments=None, error=None):
        # At most the fixture driver and one heartbeat task. Each wire exchange
        # has a total deadline; this lock preserves one stdout reader.
        async with self.lock:
            return await self._request(operation, arguments, error)

    async def _request(self, operation, arguments=None, error=None):
        self.request_id += 1
        request_id = self.request_id
        value = {"version": 1, "owner": self.owner, "id": request_id,
                 "op": operation, "args": arguments or {}}
        body = json.dumps(value, ensure_ascii=False).encode()
        if len(body) > FRAME_LIMIT:
            raise FixtureError("REQUEST_SIZE")
        self.process.stdin.write(struct.pack(">I", len(body)) + body)
        await asyncio.wait_for(self.process.stdin.drain(), 1)
        deadline = time.monotonic() + 4
        while time.monotonic() < deadline:
            reply = await asyncio.wait_for(self.read_frame(self.process), max(0.001, deadline - time.monotonic()))
            if reply.get("owner") != self.owner:
                raise FixtureError("REPLY_OWNER")
            if reply.get("event") == "status":
                if not isinstance(reply.get("revision"), int) or reply["revision"] <= self.revision:
                    raise FixtureError("STATUS_REVISION")
                self.revision = reply["revision"]
                continue
            if reply.get("id") != request_id:
                raise FixtureError("REPLY_ID")
            if error is not None:
                if reply.get("ok") is not False or reply.get("error") != error:
                    raise FixtureError(f"{operation}:EXPECTED_{error}")
                return None
            if reply.get("ok") is not True:
                raise FixtureError(reply.get("error", "INVALID_REPLY"), operation)
            return reply["result"]
        raise FixtureError("REPLY_DEADLINE")

    async def ready(self, runtime):
        deadline = time.monotonic() + 18
        while time.monotonic() < deadline:
            result = await self.request("status")
            if result.get("runtime") != runtime:
                raise FixtureError(f"RUNTIME_LOST:{result.get('failure', 'UNKNOWN')}")
            if result["phase"] == "ready":
                return result
            await asyncio.sleep(0.1)
        raise FixtureError("READY_DEADLINE")

    async def shutdown(self):
        await self.request("shutdown")
        await asyncio.wait_for(self.process.wait(), 4)
        if self.process.returncode != 0:
            raise FixtureError("AGENT_SHUTDOWN")


async def terminate(process):
    if process.returncode is not None:
        return
    process.terminate()
    try:
        await asyncio.wait_for(process.wait(), 3)
    except asyncio.TimeoutError:
        process.kill()
        await asyncio.wait_for(process.wait(), 3)


def room_config(directory):
    files = list(directory.glob("room-*.json"))
    if len(files) != 1:
        raise FixtureError("ROOM_PROFILE_COUNT")
    return json.loads(files[0].read_bytes())


async def lifecycle(agent_binary, server, root, children, traffic=False):
    paths = [root / "alice", root / "bob"]
    agents = []
    for path in paths:
        agent = await Agent.launch(agent_binary, path)
        agents.append(agent)
        children.append(agent.process)
    for name, agent in zip(("alice", "bob"), agents):
        await agent.request("rooms", error="AUTH_REQUIRED")
        account = await agent.request("register", {"server": server,
                    "identifier": f"{name}-{secrets.token_hex(8)}@example.com", "password": "fixture-account-password"})
        if not account.get("authenticated") or "token" in account:
            raise FixtureError("ACCOUNT_PROJECTION")
        await agent.request("start", {"config": {}}, error="INVALID_ARGUMENTS")
    room = (await agents[0].request("create_room", {"name": "Minecraft fixture", "password": "fixture-room-password"}))["room"]
    await agents[1].request("join_room", {"code": room["code"], "password": "wrong"}, error="FORBIDDEN")
    joined = (await agents[1].request("join_room", {"code": room["code"], "password": "fixture-room-password"}))["room"]
    if joined["id"] != room["id"]:
        raise FixtureError("ROOM_ID")
    for agent in agents:
        own = (await agent.request("rooms"))["rooms"]
        if len(own) != 1 or set(own[0]) != {"id", "code", "name", "cidr", "role"}:
            raise FixtureError("ROOM_PROJECTION")
        await agent.request("connect_room", {"room": "room-missing"}, error="ROOM_UNKNOWN")
    runtime_ids, status = [], []
    for selector, agent in zip((room["code"], room["id"]), agents):
        runtime = (await agent.request("connect_room", {"room": selector}))["runtime"]
        runtime_ids.append(runtime)
        status.append(await agent.ready(runtime))
        await agent.request("logout", error="RUNTIME_BUSY")
        await agent.request("stop", {"runtime": "0" * 32}, error="RUNTIME_CHANGED")
    if status[0]["virtual_ip"] == status[1]["virtual_ip"]:
        raise FixtureError("VIRTUAL_IP_COLLISION")
    if traffic:
        from traffic_fixture import transfer
        await transfer(agents, runtime_ids, status[0]["peer_id"], FixtureError)
    identity = room_config(paths[0])["node"]["public_key"]
    await agents[0].request("stop", {"runtime": runtime_ids[0]})
    new_runtime = (await agents[0].request("connect_room", {"room": room["id"]}))["runtime"]
    await agents[0].ready(new_runtime)
    if new_runtime == runtime_ids[0] or room_config(paths[0])["node"]["public_key"] != identity:
        raise FixtureError("RECONNECT_IDENTITY")
    await agents[0].request("stop", {"runtime": runtime_ids[0]}, error="RUNTIME_CHANGED")
    await agents[0].request("stop", {"runtime": new_runtime})
    await agents[1].request("stop", {"runtime": runtime_ids[1]})
    await agents[0].shutdown()
    # The original account and room identity survive a full native-process replacement.
    replacement = await Agent.launch(agent_binary, paths[0])
    children.append(replacement.process)
    if replacement.owner == agents[0].owner or not (await replacement.request("status"))["account"]["authenticated"]:
        raise FixtureError("PROCESS_REPLACEMENT")
    restored = (await replacement.request("connect_room", {"room": room["id"]}))["runtime"]
    await replacement.ready(restored)
    if room_config(paths[0])["node"]["public_key"] != identity:
        raise FixtureError("RESTORED_IDENTITY")
    # EOF with a running daemon must revoke the parent and cleanly end that child.
    replacement.process.stdin.close()
    await asyncio.wait_for(replacement.process.wait(), 5)
    if replacement.process.returncode != 0:
        raise FixtureError("RUNNING_EOF")
    await agents[1].request("leave_room", {"room": room["id"]})
    if (await agents[1].request("rooms"))["rooms"]:
        raise FixtureError("LEAVE_ROOM")
    await agents[1].request("logout")
    await agents[1].shutdown()
    signed_out = await Agent.launch(agent_binary, paths[1])
    children.append(signed_out.process)
    await signed_out.request("rooms", error="AUTH_REQUIRED")
    await signed_out.shutdown()


async def run(control_binary, agent_binary, traffic=False):
    target = PROJECT / "native/target"
    target.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="control-fixture-", dir=target) as temporary:
        root = Path(temporary)
        os.chmod(root, 0o700)
        port = available_port()
        env = environment() | {"CONTROL_BIND": f"127.0.0.1:{port}",
              "DB_PATH": str(root / "control.db"), "JWT_SECRET": secrets.token_hex(32),
              "LOG_UPLOAD_DIR": str(root / "support")}
        children = []
        try:
            control = await asyncio.create_subprocess_exec(str(control_binary), cwd=root,
                      env=env, stdin=asyncio.subprocess.DEVNULL,
                      stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
            children.append(control)
            deadline = time.monotonic() + 15
            while not await asyncio.to_thread(healthy, port):
                if control.returncode is not None or time.monotonic() >= deadline:
                    raise FixtureError("CONTROL_STARTUP")
                await asyncio.sleep(0.1)
            await lifecycle(agent_binary, f"http://127.0.0.1:{port}", root, children, traffic)
            print("PASS real Control: account/room projection, two managed daemons, fenced stop/reconnect, profile restore, running EOF, leave/logout")
        finally:
            for child in reversed(children):
                await terminate(child)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--control", type=Path, required=True)
    parser.add_argument("--agent", type=Path, required=True)
    parser.add_argument("--traffic", action="store_true", help="also verify real encrypted application TCP and service revocation")
    args = parser.parse_args()
    for path in (args.control, args.agent):
        if not path.is_file():
            parser.error("required_executable_missing")
    try:
        asyncio.run(run(args.control.resolve(), args.agent.resolve(), args.traffic))
    except (FixtureError, OSError, asyncio.TimeoutError, asyncio.IncompleteReadError) as error:
        print(f"FAIL Control fixture: {error if isinstance(error, FixtureError) else type(error).__name__}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
