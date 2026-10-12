"""Fixture namespace, JVM execution and create-only publication contracts."""
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import fixture_relocation as fixture
from artifact_checks import VerificationError


class FixtureRelocationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.classes = self.root / 'classes'; self.classes.mkdir()
        self.metadata = self.root / 'fabric.mod.json'
        self.metadata.write_text(json.dumps({'schemaVersion': 1, 'id': 'p2wlan_local_game_test', 'version': '1.0.0',
                                            'entrypoints': {'main': ['io.p2wlan.mc.fabric.fixture.Entry']}}))

    def compile_probe(self):
        javac = shutil.which('javac')
        self.assertIsNotNone(javac, 'fixture relocation JVM regression requires the project JDK')
        sources = self.root / 'sources'; sources.mkdir()
        probe = sources / 'GameProbe.java'
        probe.write_text('package io.p2wlan.mc.game.fixture; public class GameProbe { public static String name(){return GameProbe.class.getName();} }')
        entry = sources / 'Entry.java'
        entry.write_text('package io.p2wlan.mc.fabric.fixture; import io.p2wlan.mc.game.fixture.GameProbe; public class Entry { public static void main(String[] args) throws Exception { if(!Class.forName("io.p2wlan.mc.game.fixture.GameProbe").getName().equals(GameProbe.name()))throw new AssertionError(); System.out.println(GameProbe.name()); } }')
        subprocess.run([javac, '--release', '17', '-d', str(self.classes), str(probe), str(entry)], check=True, capture_output=True, timeout=20)

    def test_relocated_references_reflection_and_class_paths_execute_on_jvm(self):
        self.compile_probe()
        payload, count = fixture.build([self.classes], self.metadata)
        self.assertEqual(count, 2)
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            self.assertIn('io/p2wlan/mc/fabric/game/fixture/GameProbe.class', archive.namelist())
            self.assertFalse(any(name.startswith('io/p2wlan/mc/game/') for name in archive.namelist()))
            for name in archive.namelist():
                if name.endswith('.class'):
                    self.assertNotIn(b'io/p2wlan/mc/game/', archive.read(name))
        output = self.root / 'fixture.jar'; fixture.publish(output, payload)
        result = subprocess.run([shutil.which('java'), '-cp', str(output), 'io.p2wlan.mc.fabric.fixture.Entry'],
                                check=True, capture_output=True, text=True, timeout=10)
        self.assertEqual(result.stdout.strip(), 'io.p2wlan.mc.fabric.game.fixture.GameProbe')
        duplicate, _ = fixture.build([self.classes], self.metadata)
        self.assertEqual(duplicate, payload)
        fixture.publish(output, duplicate)
        with self.assertRaisesRegex(VerificationError, 'pack_output_exists'):
            fixture.publish(output, payload + b'changed')
        self.assertEqual(output.read_bytes(), payload)

    def test_empty_duplicate_wrong_owner_and_symlink_inputs_fail_closed(self):
        with self.assertRaisesRegex(VerificationError, 'fixture_relocation_empty'):
            fixture.build([self.classes], self.metadata)
        self.compile_probe()
        with self.assertRaisesRegex(VerificationError, 'fixture_relocation_class_owner'):
            fixture.build([self.classes, self.classes], self.metadata)
        wrong = self.classes / 'io/p2wlan/mc/common/Hidden.class'
        wrong.parent.mkdir(parents=True); wrong.write_bytes(b'not a fixture class')
        with self.assertRaisesRegex(VerificationError, 'fixture_relocation_class_owner'):
            fixture.build([self.classes], self.metadata)
        wrong.unlink()
        link = self.classes / 'foreign'; link.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(VerificationError):
            fixture.build([self.classes], self.metadata)

    def test_only_fixture_metadata_and_bounded_class_inventory_are_admitted(self):
        self.compile_probe()
        with patch.object(fixture, 'MAX_CLASSES', 1), self.assertRaisesRegex(VerificationError, 'fixture_relocation_capacity'):
            fixture.build([self.classes], self.metadata)
        self.metadata.write_text('{}')
        with self.assertRaisesRegex(VerificationError, 'fixture_relocation_metadata'):
            fixture.build([self.classes], self.metadata)


if __name__ == '__main__':
    unittest.main()
