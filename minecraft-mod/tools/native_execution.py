"""Explicit native/Wine build-info execution and bounded Windows PE decisions.

Wine is a build-tool dependency, not real-Windows filesystem/game acceptance.
The native bundle owner still owns source fencing and atomic publication.
"""
from dataclasses import dataclass
import os
from pathlib import Path
import shutil
import struct
import subprocess

METHODS = {"native-build-info", "wine-build-info"}


class ExecutionError(ValueError):
    def __init__(self, code):
        self.code = code  # stable reason code, never a log-text control signal
        super().__init__(code)


def check_method(platform, method):
    if method not in METHODS or method == "wine-build-info" and platform != "windows-x86_64":
        raise ExecutionError("native_verification_method_invalid")


@dataclass(frozen=True)
class Execution:
    platform: str
    wine: Path | None = None

    @property
    def method(self):
        return "wine-build-info" if self.wine else "native-build-info"

    @property
    def cargo_target(self):
        return "x86_64-pc-windows-gnu" if self.wine else None

    def build_environment(self, env):
        result = env.copy()
        if self.wine:
            compiler = shutil.which("x86_64-w64-mingw32-gcc")
            archiver = shutil.which("x86_64-w64-mingw32-ar")
            if not compiler or not archiver:
                raise ExecutionError("native_cross_toolchain_missing")
            result.setdefault("CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER", compiler)
            result.setdefault("CC_x86_64_pc_windows_gnu", compiler)
            result.setdefault("AR_x86_64_pc_windows_gnu", archiver)
        return result

    def run_info(self, binary, prefix):
        command = [str(binary), "--build-info"]
        env = os.environ.copy()
        timeout = 5
        if self.wine:
            # One deliberately isolated, local build prefix. Never use the
            # user's default Wine prefix or inherit DLL overrides/downloads.
            if not prefix.is_absolute() or prefix.is_symlink() or not prefix.is_dir():
                raise ExecutionError("native_wine_prefix_invalid")
            env.update(WINEPREFIX=str(prefix), WINEDEBUG="-all", WINEDLLOVERRIDES="mscoree,mshtml=")
            command.insert(0, str(self.wine))
            timeout = 30  # includes Wine's local first-use initialization
        # The locally built agent's fixed --build-info branch emits <4 KiB and
        # starts no account/runtime/network tasks. Toolchains/runners are trusted
        # build inputs, not arbitrary code accepted from a remote peer.
        try:
            result = subprocess.run(command, env=env, cwd=binary.parent, stdout=subprocess.PIPE,
                                    stderr=subprocess.DEVNULL, timeout=timeout)
        finally:
            if self.wine:
                # Stop only this tool-owned prefix, not the user's Wine session.
                # No identity proof is published while cleanup is uncertain.
                server = self.wine.parent / "wineserver"
                try:
                    for action in ("-k", "-w"):
                        stopped = subprocess.run([str(server), action], env=env, stdout=subprocess.DEVNULL,
                                                 stderr=subprocess.DEVNULL, timeout=5)
                        if stopped.returncode:
                            raise ExecutionError("native_wine_cleanup_failed")
                except (OSError, subprocess.SubprocessError) as error:
                    raise ExecutionError("native_wine_cleanup_failed") from error
        if result.returncode:
            raise ExecutionError("native_build_info_execution_failed")
        if len(result.stdout) > 4096:
            raise ExecutionError("native_build_info_size")
        return result.stdout


def select(host, target=None, wine=None):
    selected = target or host
    if selected == host:
        if wine is not None:
            raise ExecutionError("native_wine_requires_foreign_windows_target")
        return Execution(selected)
    if selected != "windows-x86_64" or not host.startswith(("macos-", "linux-")) or wine is None:
        raise ExecutionError("native_foreign_execution_not_supported")
    runner = Path(wine)
    if not runner.is_absolute() or not runner.is_file() or not os.access(runner, os.X_OK):
        raise ExecutionError("native_wine_runner_invalid")
    runner = runner.resolve()
    server = runner.parent / "wineserver"
    if not server.is_file() or not os.access(server, os.X_OK):
        raise ExecutionError("native_wine_server_invalid")
    return Execution(selected, runner)


def check_windows_pe(binary):
    """Require an executable AMD64 PE32+ console image, not x86/ARM64/a DLL.

    Only bounded headers are parsed; this is not a full Windows image-loader
    validator. Successful build-info execution remains mandatory afterwards.
    """
    with binary.open("rb") as stream:
        dos = stream.read(64)
        if len(dos) != 64 or dos[:2] != b"MZ":
            raise ExecutionError("native_windows_pe_invalid")
        offset = struct.unpack_from("<I", dos, 60)[0]
        if not 64 <= offset <= 1024 * 1024:
            raise ExecutionError("native_windows_pe_invalid")
        stream.seek(offset)
        header = stream.read(24)
        if len(header) != 24 or header[:4] != b"PE\0\0":
            raise ExecutionError("native_windows_pe_invalid")
        machine, sections, _, _, _, size, flags = struct.unpack_from("<HHIIIHH", header, 4)
        if machine != 0x8664 or not 1 <= sections <= 96 or not 112 <= size <= 4096 or flags & 0x2002 != 2:
            raise ExecutionError("native_windows_pe_invalid")
        optional = stream.read(size)
        if len(optional) != size or struct.unpack_from("<H", optional)[0] != 0x20b \
                or struct.unpack_from("<H", optional, 68)[0] != 3:
            raise ExecutionError("native_windows_pe_invalid")
