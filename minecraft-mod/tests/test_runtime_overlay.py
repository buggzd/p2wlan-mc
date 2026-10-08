import hashlib
import importlib.util
from pathlib import Path
import tempfile
import tomllib
import unittest

PROJECT = Path(__file__).resolve().parents[1]
ROOT = PROJECT.parent
spec = importlib.util.spec_from_file_location("prepare_runtime", PROJECT / "tools/prepare_runtime.py")
overlay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(overlay)

class RuntimeOverlayTests(unittest.TestCase):
    def test_changed_or_ambiguous_anchor_fails_closed(self):
        for text in ("absent", "target target"):
            with self.assertRaises(overlay.ContractError):
                overlay.replace_once(text, "target", "replacement", "fixture")

    def test_generated_runtime_does_not_mutate_any_upstream_source(self):
        upstream = ROOT / "client/daemon"
        files = list(upstream.rglob("*.rs")) + [upstream / "Cargo.toml"]
        before = {p: hashlib.sha256(p.read_bytes()).digest() for p in files}
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "generated"
            overlay.prepare(ROOT, destination)
            runtime = (destination / "src/lib/daemon/run.rs").read_text()
            self.assertIn("if !userspace", runtime)
            self.assertIn("run_with_packet_factory", runtime)
            self.assertIn("factory(&self.config)", runtime)
            dependencies = tomllib.loads((destination / "Cargo.toml").read_text())["dependencies"]
            self.assertTrue((destination / dependencies["p2wlan-mc-bridge"]["path"] / "Cargo.toml").is_file())
            self.assertIn("Option<mc_embed::PacketInterface>", (destination / "src/lib/daemon/dataplane_tasks.rs").read_text())
        self.assertEqual(before, {p: hashlib.sha256(p.read_bytes()).digest() for p in files})

    def test_generated_descendant_cannot_redirect_writes_into_upstream(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "generated"
            root.mkdir()
            outside = Path(directory) / "outside"
            outside.mkdir()
            (root / "src").symlink_to(outside, target_is_directory=True)
            with self.assertRaises(overlay.ContractError):
                overlay.prepare(ROOT, root)
            self.assertFalse(list(outside.iterdir()))

    def test_generated_symlink_root_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "outside"
            target.mkdir()
            link = Path(directory) / "generated"
            link.symlink_to(target, target_is_directory=True)
            with self.assertRaises(overlay.ContractError):
                overlay.prepare(ROOT, link)
            self.assertFalse(list(target.iterdir()))

if __name__ == "__main__":
    unittest.main()
