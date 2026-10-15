"""Exercise the cleanup boundary against real temporary Git repositories."""
import contextlib
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1] / "tools"
sys.path.insert(0, str(TOOLS))
import clean_builds


class CleanBuildTests(unittest.TestCase):
    def repository(self):
        directory = self.enterContext(tempfile.TemporaryDirectory())
        root = Path(directory).resolve()
        subprocess.run(["git", "init", "-q", str(root)], check=True)
        self.write(root, ".gitignore", b"build/\ntarget/\nrun/\n.gradle/\n.evidence/\n")
        self.write(root, "minecraft-mod/java/common/build.gradle", b"plugins {}\n")
        self.write(root, "minecraft-mod/java/native-bundle/build.gradle", b"plugins {}\n")
        subprocess.run(["git", "add", "."], cwd=root, check=True)
        return root

    @staticmethod
    def write(root, relative, value=b"retained or disposable bytes"):
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value)
        return path

    def test_preview_and_apply_keep_packages_fixtures_caches_and_untracked_modules(self):
        root = self.repository()
        fixture = self.write(root, "minecraft-mod/java/common/src/test/resources/fixture/build.gradle")
        subprocess.run(["git", "add", str(fixture.relative_to(root))], cwd=root, check=True)
        disposable = [
            "minecraft-mod/native/target/debug/deps/agent",
            "minecraft-mod/native/target/release/agent",
            "minecraft-mod/native/target/x86_64-pc-windows-gnu/release/agent.exe",
            "minecraft-mod/java/common/build/classes/java/main/Main.class",
            "minecraft-mod/java/common/build/libs/main.jar",
            "minecraft-mod/java/common/build/tmp/compileJava/output",
            "minecraft-mod/java/common/build/generated/sources/main/Main.java",
            "minecraft-mod/java/common/build/resources/main/settings.json",
            "minecraft-mod/java/native-bundle/build/resources/main/copied-agent",
        ]
        retained = [
            "minecraft-mod/native/target/toolchains/compiler",
            "minecraft-mod/native/target/go-cache/cache",
            "minecraft-mod/native/target/unsupported-target/release/agent",
            "minecraft-mod/java/common/build/distributions/player.zip",
            "minecraft-mod/java/common/build/classes/java/localGameFixture/Fixture.class",
            "minecraft-mod/java/common/build/generated/sources/fixtureCommon/Api.java",
            "minecraft-mod/java/common/build/tmp/compileFixtureJava/output",
            "minecraft-mod/java/common/build/libs/installed-fixture.jar",
            "minecraft-mod/java/common/build/local-game-fixture/world/level.dat",
            "minecraft-mod/java/common/build/reports/verification.txt",
            "minecraft-mod/java/common/build/test-results/test.xml",
            "minecraft-mod/java/common/run/world/level.dat",
            "minecraft-mod/java/common/.gradle/cache",
            "minecraft-mod/java/native-bundle/build/generatedResources/agent",
            "minecraft-mod/.evidence/old-build/build.gradle",
            "minecraft-mod/.evidence/old-build/build/classes/Main.class",
            "minecraft-mod/java/untracked/build.gradle",
            "minecraft-mod/java/untracked/build/classes/Main.class",
            "minecraft-mod/java/common/src/test/resources/fixture/build/classes/Main.class",
        ]
        for relative in disposable + retained:
            self.write(root, relative)
        with contextlib.redirect_stdout(io.StringIO()) as output:
            preview = clean_builds.clean(root)
        self.assertIn("DRY-RUN", output.getvalue())
        self.assertTrue(all((root / value).exists() for value in disposable + retained))
        self.assertEqual(sum(size for _, size in preview), sum((root / value).stat().st_size for value in disposable))
        with contextlib.redirect_stdout(io.StringIO()) as output:
            clean_builds.clean(root, True)
        self.assertIn("APPLIED", output.getvalue())
        self.assertTrue(all(not (root / value).exists() for value in disposable))
        self.assertTrue(all((root / value).is_file() for value in retained))
        self.assertEqual(clean_builds.plan(root), [])

    def test_all_targets_are_checked_before_any_deletion_for_tracked_content(self):
        root = self.repository()
        first = self.write(root, "minecraft-mod/native/target/debug/disposable")
        protected = self.write(root, "minecraft-mod/java/common/build/libs/tracked.jar")
        subprocess.run(["git", "add", "-f", str(protected.relative_to(root))], cwd=root, check=True)
        with self.assertRaisesRegex(clean_builds.CleanError, "clean_tracked_content"):
            clean_builds.clean(root, True)
        self.assertTrue(first.is_file())
        self.assertTrue(protected.is_file())

    def test_unignored_output_refuses_the_entire_plan(self):
        root = self.repository()
        first = self.write(root, "minecraft-mod/native/target/debug/disposable")
        self.write(root, ".gitignore", b"build/\n")
        with self.assertRaisesRegex(clean_builds.CleanError, "clean_output_not_ignored"):
            clean_builds.clean(root, True)
        self.assertTrue(first.is_file())

    def test_target_ancestor_and_descendant_symlinks_refuse_without_touching_outside(self):
        for position in ("target", "ancestor", "descendant"):
            with self.subTest(position=position):
                root = self.repository()
                outside = root / "outside"
                outside.mkdir()
                preserved = self.write(root, "outside/important", b"keep")
                base = root / "minecraft-mod/native/target"
                base.mkdir(parents=True)
                if position == "target":
                    (base / "debug").symlink_to(outside, target_is_directory=True)
                elif position == "ancestor":
                    base.rmdir()
                    base.symlink_to(outside, target_is_directory=True)
                else:
                    (base / "debug").mkdir()
                    (base / "debug/link").symlink_to(outside, target_is_directory=True)
                with self.assertRaisesRegex(clean_builds.CleanError, "clean_symlink"):
                    clean_builds.clean(root, True)
                self.assertEqual(preserved.read_bytes(), b"keep")

    def test_empty_repository_is_idempotent_and_cli_accepts_no_arbitrary_path(self):
        root = self.repository()
        for apply in (False, True, True):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(clean_builds.clean(root, apply), [])
            self.assertIn("0 targets; 0 bytes", output.getvalue())
        with patch.object(sys, "argv", ["clean_builds.py", "--path", "outside"]), \
                contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
            clean_builds.main()
        self.assertEqual(error.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
