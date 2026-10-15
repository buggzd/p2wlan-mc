#!/usr/bin/env python3
"""Preview or remove disposable Mod compilation outputs after stopping builds."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import stat
import subprocess

ROOT = Path(__file__).resolve().parents[2]
JAVA_OUTPUTS = frozenset({
    "classes", "generated", "resources", "tmp", "libs", "devlibs", "remapped",
    "sourceSets", "processIncludeJars", "loom-cache", "moddev",
})
NATIVE_TARGETS = (
    "aarch64-apple-darwin", "x86_64-apple-darwin", "aarch64-unknown-linux-gnu",
    "x86_64-unknown-linux-gnu", "x86_64-pc-windows-gnu", "x86_64-pc-windows-msvc",
)
PRESERVED = frozenset({
    "distributions", "generatedresources", "run", ".gradle", ".evidence",
    "reports", "test-results",
})


class CleanError(ValueError):
    pass


def tracked_paths(root):
    result = subprocess.run(["git", "ls-files", "-z"], cwd=root, check=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return {os.fsdecode(value) for value in result.stdout.split(b"\0") if value}


def check_ancestry(root, path):
    try:
        relative = path.relative_to(root)
    except ValueError as error:
        raise CleanError("clean_path_outside_repository") from error
    current = root
    for part in (None, *relative.parts):
        if part is not None:
            current /= part
        if current.is_symlink():
            raise CleanError("clean_symlink")


def preserved(path):
    name = path.name.lower()
    return "fixture" in name or name in PRESERVED or "evidence" in name


def java_targets(path):
    """Split only ancestors of protected fixture outputs; retain their data."""
    if preserved(path):
        return [], True
    if path.is_symlink():
        raise CleanError("clean_symlink")
    mode = path.stat().st_mode
    if stat.S_ISREG(mode):
        return [path], False
    if not stat.S_ISDIR(mode):
        raise CleanError("clean_special_file")
    targets, protected = [], False
    for child in sorted(path.iterdir()):
        selected, keep = java_targets(child)
        targets.extend(selected)
        protected |= keep
    return (targets, True) if protected else ([path], False)


def candidates(root, tracked):
    native = root / "minecraft-mod/native/target"
    selected = [native / profile for profile in ("debug", "release")]
    selected += [native / target / profile for target in NATIVE_TARGETS for profile in ("debug", "release")]
    # Only tracked Gradle modules own build roots. Fixture worlds may contain
    # their own build.gradle; never discover additional modules by recursion.
    modules = sorted(Path(value).parent for value in tracked
                     if value.startswith("minecraft-mod/java/") and Path(value).name == "build.gradle")
    for module in modules:
        if any(preserved(Path(part)) for part in module.parts):
            continue
        build = root / module / "build"
        check_ancestry(root, build)
        for name in sorted(JAVA_OUTPUTS):
            path = build / name
            check_ancestry(root, path)
            if path.exists():
                selected.extend(java_targets(path)[0])
    return sorted(selected)


def tree_bytes(path):
    if path.is_symlink():
        raise CleanError("clean_symlink")
    mode = path.stat().st_mode
    if stat.S_ISREG(mode):
        return path.stat().st_size
    if not stat.S_ISDIR(mode):
        raise CleanError("clean_special_file")
    return sum(tree_bytes(child) for child in path.iterdir())


def plan(root):
    tracked = tracked_paths(root)
    paths = []
    for path in candidates(root, tracked):
        check_ancestry(root, path)
        if not path.exists():
            continue
        relative = path.relative_to(root).as_posix()
        if any(value == relative or value.startswith(relative + "/") for value in tracked):
            raise CleanError("clean_tracked_content")
        paths.append((path, tree_bytes(path)))
    if paths:
        requested = {path.relative_to(root).as_posix() for path, _ in paths}
        result = subprocess.run(["git", "check-ignore", "--no-index", "-z", "--stdin"], cwd=root,
                                input=b"".join(os.fsencode(value) + b"\0" for value in sorted(requested)),
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode not in (0, 1):
            raise CleanError("clean_ignore_check_failed")
        ignored = {os.fsdecode(value) for value in result.stdout.split(b"\0") if value}
        if ignored != requested:
            raise CleanError("clean_output_not_ignored")
    return paths


def clean(root, apply=False):
    # Validate the entire plan before removing its first entry. Stop Gradle,
    # Cargo and game fixtures before use; these outputs are not a live cache API.
    paths = plan(root)
    for path, size in paths:
        print(f"{size} bytes\t{path.relative_to(root).as_posix()}")
    if apply:
        for path, _ in paths:
            if path.is_dir():
                shutil.rmtree(path)
            else:
                path.unlink()
    print(f"{'APPLIED' if apply else 'DRY-RUN'} {len(paths)} targets; {sum(size for _, size in paths)} bytes (logical file sizes)")
    return paths


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Remove the validated compilation outputs")
    args = parser.parse_args()
    try:
        clean(ROOT, args.apply)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        parser.exit(1, f"Clean failed: {str(error) if isinstance(error, CleanError) else 'CLEAN_IO'}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
