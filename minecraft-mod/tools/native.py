#!/usr/bin/env python3
"""Run the independent native workspace without editing upstream manifests."""
import argparse
from pathlib import Path
import subprocess
import tomllib

from prepare_runtime import prepare

PROJECT = Path(__file__).resolve().parents[1]


def build_command(command, arguments):
    if any(value.startswith("--manifest-path") for value in arguments):
        raise ValueError("manifest_override_not_allowed")
    result = ["cargo", command, "--manifest-path", str(PROJECT / "native/Cargo.toml")]
    if command == "fmt":
        # Cargo fmt --all also formats local path dependencies, including the
        # upstream source. Always select only the Mod-owned workspace packages.
        if any(value not in ("--all", "--", "--check") for value in arguments):
            raise ValueError("formatter_argument_not_allowed")
        manifest = tomllib.loads((PROJECT / "native/Cargo.toml").read_text())
        for member in manifest["workspace"]["members"]:
            package = tomllib.loads((PROJECT / "native" / member / "Cargo.toml").read_text())
            result.extend(["--package", package["package"]["name"]])
        result.extend(value for value in arguments if value != "--all")
    else:
        result.extend(arguments)
    return result


def overlay_format_command(arguments):
    # Overlay sources are Mod-owned. skip_children forbids rustfmt following
    # module paths outside these explicit files, just as package selection
    # prevents Cargo fmt from rewriting upstream path dependencies.
    result = ["rustfmt", "--edition", "2021", "--config", "skip_children=true"]
    if "--check" in arguments:
        result.append("--check")
    return result + [str(path) for path in sorted((PROJECT / "native/runtime-overlay").glob("*.rs"))]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("test", "check", "build", "fmt", "clippy"))
    parser.add_argument("args", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    try:
        command = build_command(args.command, args.args)
    except ValueError as error:
        parser.error(str(error))
    if args.command == "fmt":
        status = subprocess.call(overlay_format_command(args.args), cwd=PROJECT)
        if status:
            return status
    prepare()
    return subprocess.call(command, cwd=PROJECT)


if __name__ == "__main__":
    raise SystemExit(main())
