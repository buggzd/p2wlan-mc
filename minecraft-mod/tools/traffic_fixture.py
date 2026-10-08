"""Bounded loopback target fixture; not a Minecraft or Java-world substitute."""
import asyncio
import contextlib
import hmac
import secrets
import time

MARKER = b"P2MC\x00\x00\x00\x01"
CHUNK = 8192
STREAMS = 3
PAYLOAD_BYTES = 128 * 1024
PATH_TIMEOUT = 15
ADMISSION_PENDING = {"UNKNOWN_PEER", "ROOM_REVOKED", "SESSION_MISSING", "SESSION_CONTENDED"}


async def wait_peer_paths(agents, runtimes, peers, error_type):
    # Observe existing authorities, not a fixed sleep or a second path owner.
    # Waiting here proves only readiness for the transfer test, not delivery.
    async def observe():
        while True:
            ready = True
            for agent, runtime, peer in zip(agents, runtimes, peers, strict=True):
                await agent.request("ping")
                try:
                    reply = await agent.request("peer_status", {"runtime": runtime, "peer": peer})
                except error_type as error:
                    if error.operation != "peer_status" or error.code not in ADMISSION_PENDING:
                        raise
                    ready = False
                    continue
                if reply.get("runtime") != runtime or reply.get("peer") != peer:
                    raise error_type("PEER_VIEW_IDENTITY")
                view = reply["view"]
                ready &= view.get("online") is True and (view.get("path") == "relay" or
                    (view.get("path") == "direct" and view.get("direct_budget_ready") is True))
            if ready:
                return
            await asyncio.sleep(0.1)
    try:
        await asyncio.wait_for(observe(), PATH_TIMEOUT)
    except asyncio.TimeoutError:
        raise error_type("PEER_PATH_DEADLINE") from None


async def transfer(agents, runtimes, peer_id, error_type):
    key, world = secrets.token_bytes(32), secrets.token_hex(16)
    tasks, writers = set(), set()
    accepted, target_bytes, completed, target_failure = 0, 0, 0, None
    stop = asyncio.Event()

    async def guard(reader, writer):
        nonlocal accepted, target_bytes, completed, target_failure
        task = asyncio.current_task()
        if len(tasks) >= STREAMS:
            writer.close()
            return
        tasks.add(task)
        writers.add(writer)
        try:
            prelude = await asyncio.wait_for(reader.readexactly(40), 2)
            if not hmac.compare_digest(prelude, MARKER + key):
                raise error_type("TARGET_KEY")
            accepted += 1
            writer.write(b"\x01")
            await asyncio.wait_for(writer.drain(), 2)
            total = 0
            while data := await asyncio.wait_for(reader.read(CHUNK), 8):
                total += len(data)
                target_bytes += len(data)
                if total > PAYLOAD_BYTES:
                    raise error_type("TARGET_BYTE_BUDGET")
                writer.write(data)
                await asyncio.wait_for(writer.drain(), 2)
            writer.write_eof()
            await asyncio.wait_for(writer.drain(), 2)
            completed += 1  # Only genuine input EOF plus successful output EOF.
        except error_type as error:
            target_failure = error.code
        except asyncio.TimeoutError:
            target_failure = "TARGET_TIMEOUT"
        except asyncio.IncompleteReadError:
            target_failure = "TARGET_PRELUDE_EOF"
        except (ConnectionError, OSError):
            target_failure = "TARGET_IO"
        finally:
            writer.close()
            with contextlib.suppress(OSError, asyncio.TimeoutError):
                await asyncio.wait_for(writer.wait_closed(), 2)
            writers.discard(writer)
            tasks.discard(task)

    async def heartbeat():
        while not stop.is_set():
            for agent in agents:
                await agent.request("ping")
            try:
                await asyncio.wait_for(stop.wait(), 0.5)
            except asyncio.TimeoutError:
                pass

    target = await asyncio.start_server(guard, "127.0.0.1", 0, limit=CHUNK, backlog=STREAMS)
    target_port = target.sockets[0].getsockname()[1]
    timer = None
    try:
        host = await agents[0].request("host", {"runtime": runtimes[0], "world": world,
                    "port": target_port, "key": key.hex()})
        await agents[0].request("close_registration", {"runtime": runtimes[0],
                    "registration": host["registration"], "world": "0" * 32}, error="WORLD_CHANGED")
        client_peer = (await agents[1].request("status"))["peer_id"]
        await wait_peer_paths(agents, runtimes, [client_peer, peer_id], error_type)
        deadline = time.monotonic() + 15
        proxy = None
        while time.monotonic() < deadline:
            await agents[0].request("ping")
            try:
                proxy = await agents[1].request("proxy", {"runtime": runtimes[1],
                      "peer": peer_id, "port": host["port"], "world": world})
                break
            except error_type as error:
                if error.operation != "proxy" or error.code not in ADMISSION_PENDING:
                    raise
                await asyncio.sleep(0.1)
        if proxy is None:
            raise error_type("PEER_ADMISSION_DEADLINE")
        timer = asyncio.create_task(heartbeat())

        async def exchange(index):
            reader, writer = await asyncio.wait_for(asyncio.open_connection("127.0.0.1", proxy["port"], limit=CHUNK), 2)
            try:
                writer.write(MARKER + bytes.fromhex(proxy["key"]))
                payload = bytes([index + 1]) * PAYLOAD_BYTES

                async def send():
                    for start in range(0, len(payload), CHUNK):
                        writer.write(payload[start:start + CHUNK])
                        await asyncio.wait_for(writer.drain(), 2)
                    writer.write_eof()
                    await asyncio.wait_for(writer.drain(), 2)

                async def receive():
                    data = await asyncio.wait_for(reader.readexactly(len(payload)), 12)
                    if data != payload or await asyncio.wait_for(reader.read(1), 3) != b"":
                        raise error_type("TCP_PAYLOAD_OR_HALF_CLOSE")

                await asyncio.wait_for(asyncio.gather(send(), receive()), 15)
            finally:
                writer.close()
                with contextlib.suppress(OSError, asyncio.TimeoutError):
                    await asyncio.wait_for(writer.wait_closed(), 2)

        results = await asyncio.gather(*(exchange(index) for index in range(STREAMS)), return_exceptions=True)
        if any(isinstance(result, BaseException) for result in results):
            codes = []
            for agent in agents:
                status = await agent.request("status")
                view = await agent.request("peer_status", {"runtime": runtimes[len(codes)],
                    "peer": client_peer if not codes else peer_id})
                codes.append(str({name: status.get(name) for name in
                    ("gateway_failure", "failure", "admission_drops", "connections")} | {"view": view["view"]}))
            outcomes = [(type(result).__name__, len(result.partial) if isinstance(result, asyncio.IncompleteReadError) else 0)
                        for result in results if isinstance(result, BaseException)]
            raise error_type(f"TCP_TRANSFER_FAILED:accepted={accepted}:completed={completed}:target_failure={target_failure}:target_bytes={target_bytes}:outcomes={outcomes}:" + ":".join(codes))
        if accepted != STREAMS or completed != STREAMS or target_failure is not None or target_bytes != STREAMS * PAYLOAD_BYTES:
            raise error_type("TARGET_COMPLETION_OR_HALF_CLOSE")
        await agents[0].request("close_registration", {"runtime": runtimes[0],
                    "registration": host["registration"], "world": world})
        replacement = await agents[0].request("host", {"runtime": runtimes[0], "world": secrets.token_hex(16),
                    "port": target_port, "key": key.hex()})
        if replacement["port"] == host["port"] or replacement["registration"] == host["registration"]:
            raise error_type("REUSED_SERVICE_IDENTITY")
        # An old proxy may connect locally, but cannot deliver into the new host.
        reader, writer = await asyncio.wait_for(asyncio.open_connection("127.0.0.1", proxy["port"]), 2)
        try:
            writer.write(MARKER + bytes.fromhex(proxy["key"]) + b"stale-world")
            await asyncio.wait_for(writer.drain(), 2)
            with contextlib.suppress(asyncio.TimeoutError, ConnectionError):
                if await asyncio.wait_for(reader.read(1), 2):
                    raise error_type("STALE_SERVICE_DELIVERED")
        finally:
            writer.close()
            with contextlib.suppress(OSError, asyncio.TimeoutError):
                await asyncio.wait_for(writer.wait_closed(), 2)
        if accepted != STREAMS:
            raise error_type("STALE_SERVICE_TARGET_REACHED")
        await agents[1].request("close_registration", {"runtime": runtimes[1],
                    "registration": proxy["registration"], "world": world})
    finally:
        stop.set()
        try:
            if timer is not None:
                try:
                    await asyncio.wait_for(timer, 2)
                except asyncio.TimeoutError:
                    timer.cancel()
                    await asyncio.gather(timer, return_exceptions=True)
        finally:
            target.close()
            try:
                await asyncio.wait_for(target.wait_closed(), 2)
            finally:
                for writer in tuple(writers):
                    writer.close()
                for task in tuple(tasks):
                    task.cancel()
                if tasks:
                    await asyncio.wait_for(asyncio.gather(*tuple(tasks), return_exceptions=True), 3)
    print(f"PASS real daemon data plane: {STREAMS} concurrent {PAYLOAD_BYTES // 1024} KiB bidirectional TCP streams, half-close, stale service rejection")
