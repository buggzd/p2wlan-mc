#!/usr/bin/env python3
"""Build/test the independent Java project using its Gradle entry point."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

PROJECT = Path(__file__).resolve().parents[1]

def cargo_messages(output):
    messages = [json.loads(line) for line in output.splitlines()]
    for message in messages:
        if message.get("reason") == "compiler-message":
            rendered = message.get("message", {}).get("rendered")
            if rendered:
                print(rendered, file=sys.stderr, end="")
    return messages

def probe_artifacts(messages):
    binaries = {}
    for item in messages:
        if item.get("reason") != "compiler-artifact" or not item.get("executable"):
            continue
        name = item.get("target", {}).get("name")
        if name not in {"frame_probe", "agent_probe", "world_guard_probe"} or name in binaries:
            raise ValueError("native_probe_artifacts_ambiguous")
        binaries[name] = item["executable"]
    if set(binaries) != {"frame_probe", "agent_probe", "world_guard_probe"}:
        raise ValueError("native_probe_artifacts_missing")
    return binaries

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("test", "build", "wrapper", "interop", "bundle-test", "fabric-build", "fabric-server", "fabric-client", "neoforge-build", "neoforge-server", "neoforge-client", "neoforge-hmcl-pack", "forge-build", "forge-server", "forge-client", "forge-hmcl-pack", "universal-build", "universal-hmcl-pack", "account-test", "account-build", "hmcl-pack", "windows-native-test"))
    parser.add_argument("args", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.command == "universal-build":
        return subprocess.call([sys.executable, str(PROJECT / "tools/universal_build.py"), *args.args])
    if args.command == "universal-hmcl-pack":
        return subprocess.call([sys.executable, str(PROJECT / "tools/hmcl_pack.py"), "build", "--universal", *args.args])
    wrapper = PROJECT / "java" / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    gradle = str(wrapper) if wrapper.is_file() else shutil.which("gradle")
    if not gradle:
        parser.error("Gradle is required to bootstrap the wrapper")
    env = os.environ.copy()
    if sys.platform == "darwin":
        selected = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip()
        env["JAVA_HOME"] = selected
    tasks = {"neoforge-hmcl-pack": ["-PneoforgeAdapter", ":neoforge:buildHmclPack"], "neoforge-client": ["-PneoforgeAdapter", ":neoforge:verifyModJar", ":neoforge:runClient"], "neoforge-build": ["-PneoforgeAdapter", ":neoforge:build"], "neoforge-server": ["-PneoforgeAdapter", ":neoforge:verifyModJar", ":neoforge:runServer"], "windows-native-test": [":common:windowsNativePolicyTest", ":common:windowsNativeFilesystemTest"], "hmcl-pack": ["-PfabricAdapter", ":fabric:buildHmclPack"], "account-test": [":account-cli:accountTest"], "account-build": [":account-cli:check", ":account-cli:verifyAccountDistribution"], "test": [":netty-transport:check", ":common:layerNativeResourcesTest", ":common:worldSessionsTest", ":common:protocolTest", ":common:modDiagnosticsTest", ":common:worldGuardTest", ":common:nativeBundleTest", ":common:windowsNativePolicyTest", ":common:agentLifecycleTest", ":common:accountCatalogTest", ":common:controlTransportTest", ":common:screenPresentationTest", ":common:displaySettingsTest", ":common:networkCatalogTest", ":common:scheduledTaskTest", ":account-cli:accountTest"], "fabric-build": ["-PfabricAdapter", ":fabric:build"], "fabric-server": ["-PfabricAdapter", ":fabric:verifyModJar", ":fabric:runServer"], "fabric-client": ["-PfabricAdapter", ":fabric:verifyModJar", ":fabric:runClient"], "bundle-test": [":common:bundledAgentTest", ":common:bundledLifecycleTest"], "build": ["build"], "wrapper": ["wrapper", "--gradle-version", "8.14", "--distribution-type", "bin"]}
    if args.command == "interop":
        from native import build_command, prepare
        prepare()
        result = subprocess.run(build_command("build", ["-p", "p2wlan-mc-protocol", "-p", "p2wlan-mc-gateway", "--examples", "--locked", "--message-format=json"]),
                                cwd=PROJECT, text=True, stdout=subprocess.PIPE)
        try:
            artifacts = cargo_messages(result.stdout)
            if result.returncode:
                return result.returncode
            binaries = probe_artifacts(artifacts)
        except (ValueError, TypeError) as error:
            parser.error(str(error))
        tasks["interop"] = [":common:codecInteropTest", ":common:agentClientTest", ":common:worldGuardTest", ":common:uiCompletionTest", ":common:worldHostTest", ":common:joinProxyTest",
                            "-PnativeProbe=" + binaries["frame_probe"], "-PagentProbe=" + binaries["agent_probe"],
                            "-PworldGuardProbe=" + binaries["world_guard_probe"]]
    if args.command.startswith("forge-"):
        from forge_gradle import command
        selected = {"forge-hmcl-pack": [":forge:buildHmclPack"], "forge-build": [":forge:build"], "forge-server": [":forge:verifyModJar", ":forge:runServer"],
                    "forge-client": [":forge:verifyModJar", ":forge:runClient"]}[args.command]
        try:
            return subprocess.call([*command(env), "--no-daemon", *selected, *args.args], cwd=PROJECT / "java/forge", env=env)
        except (OSError, ValueError) as error:
            parser.error(str(error) if isinstance(error, ValueError) else "FORGE_BOOTSTRAP_IO")
    if args.command == "test":
        tasks["test"].append(":universal-bootstrap:bootstrapPolicyTest")
    return subprocess.call([gradle, "--no-daemon", *tasks[args.command], *args.args], cwd=PROJECT / "java", env=env)

if __name__ == "__main__":
    raise SystemExit(main())
