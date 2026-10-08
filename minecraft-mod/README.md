# P2WLAN Minecraft integration

This directory is an independent project. It does not alter the upstream Cargo workspace or existing P2WLAN client/server sources.

## Native TCP endpoint

The native bridge provides a user-space IPv4/TCP endpoint over bounded raw-IP queues. It creates no TUN device and installs no system routes. A single owner drives time, packet input/output, and socket state. Connection IDs are monotonic identities rather than reusable socket slots.

From the repository root:

```sh
python3 minecraft-mod/tools/native.py test
python3 minecraft-mod/tools/native.py fmt --all -- --check
python3 minecraft-mod/tools/native.py clippy --workspace --all-targets -- -D warnings
```

Each endpoint has at most 32 sockets, 32 KiB receive/send buffers per socket, and 128 queued IP packets per direction. The initial IP MTU is 1280 bytes. TCP inactivity timeout is 15 seconds and keep-alive is 5 seconds. Packets and connections over budget return typed failures or apply backpressure; existing work is not evicted to admit new work.

The current tests exercise real user-space TCP over an in-memory IP link. They are not Minecraft, P2WLAN encrypted transport, operating-system, or public-NAT acceptance evidence. This component alone is not an installable Mod.

## Common Java protocol

The common Java module contains no Minecraft or loader classes. It implements 64 KiB length-prefixed, strict UTF-8 JSON IPC, with duplicate-key, nesting, element-count, and numeric bounds. Native-owned status snapshots are copied and admitted only for the current process owner and increasing revision. Late cleanup from an old owner cannot close a new session.

Java 21 is required to run the project build; the common module is compiled to Java 17 bytecode. The Gradle 8.14 wrapper verifies its fixed distribution checksum. From the repository root:

```sh
python3 minecraft-mod/tools/java.py test
```

The protocol test is an explicit executable test harness, not a zero-test Gradle success. It verifies malformed/partial frames, Unicode, capacity limits, snapshot immutability, and stale-owner/revision rejection.
