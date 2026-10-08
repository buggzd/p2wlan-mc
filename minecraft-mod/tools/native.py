#!/usr/bin/env python3
"""Run the independent native workspace without editing upstream manifests."""
import argparse
from pathlib import Path
import subprocess

PROJECT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("test", "check", "build", "fmt", "clippy"))
    parser.add_argument("args", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = ["cargo", args.command, "--manifest-path", str(PROJECT / "native/Cargo.toml")]
    command.extend(args.args)
    return subprocess.call(command, cwd=PROJECT)

if __name__ == "__main__":
    raise SystemExit(main())
