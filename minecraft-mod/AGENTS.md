# Minecraft Mod project boundaries

All Minecraft integration, native adapters, build tools, tests, and product documentation belong in this directory. Do not edit the upstream Cargo workspace, client, server, scripts, workflows, or root documentation to implement the Mod.

The native workspace is independent of the upstream workspace. Reuse upstream code from this checkout, not a maintained vendored fork. Any build-only source adaptation must be exact, fail closed when its contract changes, and write only ignored generated files inside this directory.

Keep network/session/path authority in the upstream runtime. Java may consume fenced snapshots, not implement a second path state machine. Keep Minecraft classes out of common Java code and client-only classes out of dedicated-server initialization.

Run the root AGENTS.md gates after every change, plus tests for the changed project component. Small verified changes are committed individually using feat:中文 messages. Do not commit runtime identities, credentials, logs, binaries, or acceptance evidence. Documentation describes implemented behavior only; untested platform/version support must remain explicit.
