import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import sys
import tomllib
import unittest

PROJECT = Path(__file__).resolve().parents[1]
ROOT = PROJECT.parent
sys.path.insert(0, str(PROJECT / "tools"))
spec = importlib.util.spec_from_file_location("prepare_runtime", PROJECT / "tools/prepare_runtime.py")
overlay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(overlay)

class RuntimeOverlayTests(unittest.TestCase):
    def test_windows_embedded_compile_input_is_required_bounded_and_not_a_link(self):
        declaration = "src/route/windows/helpers.rs"
        resource = "src/route/windows/icmp_echo.ps1"
        source = {declaration: b'include_str!("icmp_echo.ps1")'}
        with tempfile.TemporaryDirectory() as directory:
            upstream = Path(directory)
            path = upstream / resource
            with self.assertRaisesRegex(overlay.ContractError, "embedded_resource_invalid"):
                overlay.embedded_inputs(upstream, source)
            path.parent.mkdir(parents=True)
            path.write_bytes(b"fixture script")
            self.assertEqual(overlay.embedded_inputs(upstream, source), {resource: b"fixture script"})
            for invalid in ({}, {declaration: b"missing include"}, {declaration: source[declaration] * 2}):
                with self.assertRaisesRegex(overlay.ContractError, "windows-icmp-resource"):
                    overlay.embedded_inputs(upstream, invalid)
            for data in (b"", b"x" * 65537):
                path.write_bytes(data)
                with self.assertRaisesRegex(overlay.ContractError, "embedded_resource_size"):
                    overlay.embedded_inputs(upstream, source)
            path.unlink()
            target = upstream / "outside.ps1"; target.write_bytes(b"untrusted target")
            path.symlink_to(target)
            with self.assertRaisesRegex(overlay.ContractError, "embedded_resource_invalid"):
                overlay.embedded_inputs(upstream, source)

    def test_changed_or_ambiguous_anchor_fails_closed(self):
        for text in ("absent", "target target"):
            with self.assertRaises(overlay.ContractError):
                overlay.replace_once(text, "target", "replacement", "fixture")

    def test_generated_runtime_does_not_mutate_any_upstream_source(self):
        upstream = ROOT / "client/daemon"
        files = list(upstream.rglob("*.rs")) + list(upstream.rglob("*.ps1")) + [upstream / "Cargo.toml"]
        before = {p: hashlib.sha256(p.read_bytes()).digest() for p in files}
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "generated"
            overlay.prepare(ROOT, destination)
            runtime = (destination / "src/lib/daemon/run.rs").read_text()
            self.assertIn("if !userspace", runtime)
            self.assertIn("run_with_packet_factory", runtime)
            self.assertIn("factory(&self.config)", runtime)
            self.assertIn("run_with_authorized_packet_factory", runtime)
            self.assertIn(".with_udp_owner(self.udp_transport_publication.subscribe())", runtime)
            self.assertIn("with_application_port", (destination / "src/lib/daemon/dataplane_tasks.rs").read_text())
            self.assertIn("fence.is_current()", (destination / "src/rooms.rs").read_text())
            self.assertIn('mod mc_roster;', (destination / "src/rooms.rs").read_text())
            self.assertIn('.try_lock()', (destination / "src/mc_roster.rs").read_text())
            self.assertIn('mod mc_metrics;', (destination / "src/peer.rs").read_text())
            self.assertIn('.try_read()', (destination / "src/mc_metrics.rs").read_text())
            self.assertNotIn('.await', (destination / "src/mc_metrics.rs").read_text())
            self.assertIn("deliver_inbound(&packet", (destination / "src/dataplane/core.rs").read_text())
            inbound = (destination / "src/transport/inbound.rs").read_text()
            self.assertLess(inbound.index("port.authorize_inbound(inbound, generation)"),
                            inbound.index("let decrypt_completed ="))
            self.assertIn("port.queue_inbound(&inbound_tx, inbound)", inbound)
            self.assertIn("mc_application_permit", (destination / "src/transport/sessions/decryption.rs").read_text())
            self.assertIn("AdmissionError::UnsupportedLocalFeedback", (destination / "src/dataplane/core.rs").read_text())
            self.assertNotIn("deliver_feedback", (destination / "src/mc_port.rs").read_text())
            metadata = json.loads((destination / "UPSTREAM.json").read_text())
            resource = "src/route/windows/icmp_echo.ps1"
            self.assertEqual((destination / resource).read_bytes(), (upstream / resource).read_bytes())
            self.assertEqual(metadata["upstream_sources"][resource], hashlib.sha256((upstream / resource).read_bytes()).hexdigest())
            for name, digest in metadata["adapters_sha256"].items():
                self.assertEqual(digest, hashlib.sha256((PROJECT / "native/runtime-overlay" / name).read_bytes()).hexdigest())
            for name, digest in metadata["build_tools_sha256"].items():
                self.assertEqual(digest, hashlib.sha256((PROJECT / "tools" / name).read_bytes()).hexdigest())
            dependencies = tomllib.loads((destination / "Cargo.toml").read_text())["dependencies"]
            self.assertTrue((destination / dependencies["p2wlan-mc-bridge"]["path"] / "Cargo.toml").is_file())
            self.assertIn("Option<mc_embed::PacketInterface>", (destination / "src/lib/daemon/dataplane_tasks.rs").read_text())
        self.assertEqual(before, {p: hashlib.sha256(p.read_bytes()).digest() for p in files})

    def test_application_handoff_transforms_reject_missing_repeated_or_already_adapted_sources(self):
        from runtime_fencing import adapt_dataplane, adapt_rooms, adapt_inbound, adapt_transport, adapt_decryption, adapt_udp_business_mtu
        upstream = ROOT / "client/daemon/src"
        for transform, path in ((adapt_dataplane, "dataplane/core.rs"), (adapt_rooms, "rooms.rs"),
                (adapt_inbound, "transport/inbound.rs"), (adapt_transport, "transport/sessions.rs"),
                (adapt_decryption, "transport/sessions/decryption.rs"),
                (adapt_udp_business_mtu, "udp/business_mtu.rs")):
            source = (upstream / path).read_text()
            adapted = transform(source)
            for invalid in ("", source + source, adapted):
                with self.subTest(path=path, kind=len(invalid)):
                    with self.assertRaises(overlay.ContractError):
                        transform(invalid)

    def test_original_control_families_are_classified_before_business_admission(self):
        from runtime_fencing import adapt_inbound
        source = (ROOT / "client/daemon/src/transport/inbound.rs").read_text()
        adapted = adapt_inbound(source)
        capture = adapted[adapted.index("let control = is_rekey_confirmation_packet"):adapted.index("let decrypt_completed =")]
        for parser in ("parse_direct_validation_token", "dplpmtud::parse_control_packet", "relay_probe::parse_relay_probe_token", "path_commit::parse_path_commit_token"):
            self.assertIn(parser, capture)
        self.assertIn("if !control && p2pnet_tun::IpPacket::new", capture)
        for changed in (source.replace("|| path_commit.is_some()", "|| new_control.is_some()"), source.replace("|| dplpmtud.is_some()", "")):
            with self.assertRaises(overlay.ContractError):
                adapt_inbound(changed)

    def test_identical_preparation_preserves_all_generated_file_timestamps(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "generated"
            overlay.prepare(ROOT, destination)
            before = {path: path.stat().st_mtime_ns for path in destination.rglob("*") if path.is_file()}
            overlay.prepare(ROOT, destination)
            after = {path: path.stat().st_mtime_ns for path in destination.rglob("*") if path.is_file()}
            self.assertEqual(before, after)

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

    def test_mtu_reconcile_resolves_outer_endpoint_family_and_preserves_exact_fences(self):
        from runtime_fencing import adapt_udp_business_mtu
        source = (ROOT / "client/daemon/src/udp/business_mtu.rs").read_text()
        adapted = adapt_udp_business_mtu(source)
        before = "self.socket_for_peer(Some(&snapshot.peer_id)).await"
        after = "self.socket_for_peer_endpoint(Some(&snapshot.peer_id), Some(remote_endpoint)).await"
        self.assertEqual(adapted, source.replace(before, after, 1))
        for fence in ("socket.local_addr().ok() != Some(local_endpoint)",
                      "pair.generation != epoch.network_generation",
                      "pair.remote_candidate_epoch != epoch.remote_candidate_epoch",
                      "DplpmtudPathIdentity::from_committed_validation",
                      "validation.epoch != epoch",
                      "snapshot.lifecycle == PeerPathLifecycle::Online"):
            self.assertIn(fence, adapted)

    def test_inbound_contention_keeps_original_envelope_with_shared_operation_deadline(self):
        port = (PROJECT / "native/runtime-overlay/mc_port.rs").read_text()
        helper = port[port.index("async fn ready_inbound"):port.index("/// Use the existing upstream channel", port.index("async fn ready_inbound"))]
        self.assertIn("timeout_at(deadline", helper)
        self.assertIn("Instant::now() >= deadline", helper)
        self.assertIn("self.inbound_permit(packet)", helper)
        self.assertIn("Err(AdmissionError::Contended)", helper)
        for fresh in ("peer_grant", "inbound_grant", "frame_permit", "authorize_inbound"):
            self.assertNotIn(fresh, helper)
        for method in ("queue_inbound", "deliver_inbound"):
            operation = port[port.index("pub(crate) async fn " + method):]
            operation = operation.split("pub(crate) async fn ", 1)[-1].split("pub(crate) async fn ", 1)[0]
            self.assertGreaterEqual(operation.count("ready_inbound("), 2)
            self.assertIn("timeout_at(deadline", operation)

if __name__ == "__main__":
    unittest.main()
