#!/usr/bin/env python3
"""Build/test the independent Java project using its Gradle entry point."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

PROJECT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("test", "build", "wrapper"))
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
    return subprocess.call([gradle, "--no-daemon", *tasks[args.command], *args.args], cwd=PROJECT / "java", env=env)

if __name__ == "__main__":
    raise SystemExit(main())
