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

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("test", "build", "wrapper", "interop"))
    parser.add_argument("args", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    wrapper = PROJECT / "java/gradlew"
    gradle = str(wrapper) if wrapper.is_file() else shutil.which("gradle")
    if not gradle:
        parser.error("Gradle is required to bootstrap the wrapper")
    env = os.environ.copy()
    if sys.platform == "darwin":
        selected = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip()
        env["JAVA_HOME"] = selected
    tasks = {"test": [":common:protocolTest"], "build": ["build"], "wrapper": ["wrapper", "--gradle-version", "8.14", "--distribution-type", "bin"]}
    if args.command == "interop":
        from native import build_command, prepare
        prepare()
        result = subprocess.run(build_command("build", ["-p", "p2wlan-mc-protocol", "--example", "frame_probe", "--locked", "--message-format=json"]),
                                cwd=PROJECT, text=True, stdout=subprocess.PIPE)
        if result.returncode:
            return result.returncode
        artifacts = [json.loads(line) for line in result.stdout.splitlines()]
        binaries = [item["executable"] for item in artifacts
                    if item.get("reason") == "compiler-artifact" and item.get("target", {}).get("name") == "frame_probe"
                    and item.get("executable")]
        if len(binaries) != 1:
            parser.error("native codec peer artifact is missing or ambiguous")
        tasks["interop"] = [":common:codecInteropTest", "-PnativeProbe=" + binaries[0]]
    return subprocess.call([gradle, "--no-daemon", *tasks[args.command], *args.args], cwd=PROJECT / "java", env=env)

if __name__ == "__main__":
    raise SystemExit(main())
