import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / "tools"))
import native_execution as execution


def pe(machine=0x8664, flags=2, magic=0x20b, sections=1, optional_size=240, subsystem=3):
    data = bytearray(64 + 24 + optional_size)
    data[:2] = b"MZ"; struct.pack_into("<I", data, 60, 64)
    data[64:68] = b"PE\0\0"
    struct.pack_into("<HHIIIHH", data, 68, machine, sections, 0, 0, 0, optional_size, flags)
    if optional_size >= 70:
        struct.pack_into("<H", data, 88, magic)
        struct.pack_into("<H", data, 88 + 68, subsystem)
    return data


class NativeExecutionTests(unittest.TestCase):
    def test_host_default_is_native_and_foreign_work_requires_explicit_wine(self):
        selected = execution.select("macos-aarch64")
        self.assertEqual((selected.platform, selected.method, selected.cargo_target),
                         ("macos-aarch64", "native-build-info", None))
        self.assertEqual(execution.select("windows-x86_64").method, "native-build-info")
        for target in ("windows-x86_64", "windows-x86", "windows-aarch64", "linux-x86_64", "macos-x86_64"):
            with self.assertRaises(execution.ExecutionError):
                execution.select("macos-aarch64", target)
        with self.assertRaises(execution.ExecutionError):
            execution.select("windows-x86_64", wine=Path("fixture"))

    def test_wine_plan_has_exact_windows_x64_target_and_validates_local_runner(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = Path(directory) / "wine"; runner.write_text("fixture"); runner.chmod(0o700)
            server = Path(directory) / "wineserver"; server.write_text("fixture"); server.chmod(0o700)
            for host in ("macos-aarch64", "linux-x86_64"):
                plan = execution.select(host, "windows-x86_64", runner)
                self.assertEqual(plan.method, "wine-build-info")
                self.assertEqual(plan.cargo_target, "x86_64-pc-windows-gnu")
                self.assertEqual(plan.wine, runner.resolve())
            for bad in (Path("relative"), Path(directory) / "absent", Path(directory)):
                with self.assertRaises(execution.ExecutionError):
                    execution.select("macos-aarch64", "windows-x86_64", bad)

    def test_mode_names_are_exact_and_wine_cannot_label_another_platform(self):
        for method in execution.METHODS:
            execution.check_method("windows-x86_64", method)
        for platform, method in (("macos-aarch64", "wine-build-info"), ("linux-x86_64", "wine-build-info"),
                                 ("windows-x86_64", "cross-compiled"), ("windows-x86_64", "not-executed")):
            with self.assertRaises(execution.ExecutionError): execution.check_method(platform, method)

    def test_cross_toolchain_is_required_and_caller_settings_are_not_silently_changed(self):
        plan = execution.Execution("windows-x86_64", Path("/fixture/wine"))
        with patch.object(execution.shutil, "which", side_effect=lambda name: "/fixture/" + name):
            env = plan.build_environment({"KEEP": "value", "CC_x86_64_pc_windows_gnu": "explicit-compiler"})
            self.assertEqual(env["CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER"], "/fixture/x86_64-w64-mingw32-gcc")
            self.assertEqual(env["CC_x86_64_pc_windows_gnu"], "explicit-compiler")
            self.assertEqual(env["KEEP"], "value")
        with patch.object(execution.shutil, "which", return_value=None):
            with self.assertRaisesRegex(execution.ExecutionError, "toolchain_missing"):
                plan.build_environment({})

    def test_wine_execution_is_fenced_to_local_prefix_and_cannot_enable_downloads(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "agent.exe"; prefix = Path(directory) / "prefix"; prefix.mkdir()
            plan = execution.Execution("windows-x86_64", Path("/fixture/wine"))
            with patch.dict(os.environ, {"WINEPREFIX": "/ambient/private", "WINEDLLOVERRIDES": "ambient"}), \
                    patch.object(execution.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, b'{}')) as run:
                self.assertEqual(plan.run_info(binary, prefix), b'{}')
                self.assertEqual(run.call_args_list[0].args[0], ["/fixture/wine", str(binary), "--build-info"])
                env = run.call_args_list[0].kwargs["env"]
                self.assertEqual(env["WINEPREFIX"], str(prefix))
                self.assertEqual(env["WINEDLLOVERRIDES"], "mscoree,mshtml=")
                self.assertEqual(run.call_args_list[0].kwargs["timeout"], 30)
                self.assertEqual(run.call_args_list[0].kwargs["stderr"], subprocess.DEVNULL)
                self.assertEqual([item.args[0] for item in run.call_args_list[1:]],
                                 [["/fixture/wineserver", "-k"], ["/fixture/wineserver", "-w"]])
                self.assertTrue(all(item.kwargs["timeout"] == 5 for item in run.call_args_list[1:]))
            for bad in (Path("relative"), Path(directory) / "absent"):
                with self.assertRaisesRegex(execution.ExecutionError, "prefix_invalid"): plan.run_info(binary, bad)
            link = Path(directory) / "link"; link.symlink_to(prefix, target_is_directory=True)
            with self.assertRaisesRegex(execution.ExecutionError, "prefix_invalid"): plan.run_info(binary, link)

    def test_execution_failure_and_output_budget_are_not_successful_attestations(self):
        plan = execution.Execution("macos-aarch64")
        for result in (subprocess.CompletedProcess([], 1, b'{}'), subprocess.CompletedProcess([], 0, b'x' * 4097)):
            with patch.object(execution.subprocess, "run", return_value=result):
                with self.assertRaises(execution.ExecutionError): plan.run_info(Path("/fixture/agent"), Path("/unused"))
        with patch.object(execution.subprocess, "run", side_effect=subprocess.TimeoutExpired([], 5)):
            with self.assertRaises(subprocess.TimeoutExpired): plan.run_info(Path("/fixture/agent"), Path("/unused"))

    def test_uncertain_wine_cleanup_refuses_success_after_valid_output(self):
        plan = execution.Execution("windows-x86_64", Path("/fixture/wine"))
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(execution.subprocess, "run", side_effect=[
                    subprocess.CompletedProcess([], 0, b'{}'), subprocess.CompletedProcess([], 1)]):
                with self.assertRaisesRegex(execution.ExecutionError, "cleanup_failed"):
                    plan.run_info(Path("/fixture/agent.exe"), Path(directory))

    def test_pe_headers_admit_only_bounded_amd64_console_executables(self):
        with tempfile.TemporaryDirectory() as directory:
            binary = Path(directory) / "agent.exe"; binary.write_bytes(pe())
            execution.check_windows_pe(binary)
            for bad in (b"", b"fixture", pe(machine=0x14c), pe(machine=0xaa64), pe(flags=0), pe(flags=0x2002),
                        pe(magic=0x10b), pe(sections=0), pe(sections=97), pe(optional_size=111),
                        pe(optional_size=4097), pe(subsystem=2), pe()[:-1]):
                binary.write_bytes(bad)
                with self.assertRaisesRegex(execution.ExecutionError, "pe_invalid"): execution.check_windows_pe(binary)
            for offset in (0, 63, 1024 * 1024 + 1):
                value = pe(); struct.pack_into("<I", value, 60, offset); binary.write_bytes(value)
                with self.assertRaises(execution.ExecutionError): execution.check_windows_pe(binary)


if __name__ == "__main__": unittest.main()
