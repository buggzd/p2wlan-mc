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
        self.pack_metadata = self.root / 'pack.mcmeta'
        self.pack_metadata.write_bytes(b'{\n  "pack": { "description": "P2WLAN isolated gameplay fixture", "pack_format": 15 }\n}\n')
        self.metadata.write_text(json.dumps({'schemaVersion': 1, 'id': 'p2wlan_local_game_test', 'version': '1.0.0',
                                            'entrypoints': {'main': ['io.p2wlan.mc.fabric.fixture.Entry']}}))

    def compile_probe(self, loader='fabric'):
        javac = shutil.which('javac')
        self.assertIsNotNone(javac, 'fixture relocation JVM regression requires the project JDK')
        sources = self.root / 'sources'; sources.mkdir()
        probe = sources / 'GameProbe.java'
        probe.write_text('package io.p2wlan.mc.game.fixture; public class GameProbe { public static String name(){return GameProbe.class.getName();} }')
        entry = sources / 'Entry.java'
        entry.write_text('package io.p2wlan.mc.' + loader + '.fixture; import io.p2wlan.mc.game.fixture.GameProbe; public class Entry { public static void main(String[] args) throws Exception { if(!Class.forName("io.p2wlan.mc.game.fixture.GameProbe").getName().equals(GameProbe.name()))throw new AssertionError(); System.out.println(GameProbe.name()); } }')
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

    def test_old_game_references_and_reflection_execute_in_only_the_old_namespace(self):
        self.compile_probe()
        payload, count = fixture.build([self.classes], self.metadata, '1.20.1')
        self.assertEqual(count, 2)
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            self.assertIn('io/p2wlan/mc/fabric/game/v1_20_1/fixture/GameProbe.class', archive.namelist())
            self.assertNotIn('io/p2wlan/mc/fabric/game/fixture/GameProbe.class', archive.namelist())
            self.assertFalse(any(name.startswith('io/p2wlan/mc/game/') for name in archive.namelist()))
            for name in archive.namelist():
                if name.endswith('.class'):
                    self.assertNotIn(b'io/p2wlan/mc/game/', archive.read(name))
        output = self.root / 'old-fixture.jar'; fixture.publish(output, payload)
        result = subprocess.run([shutil.which('java'), '-cp', str(output), 'io.p2wlan.mc.fabric.fixture.Entry'],
                                check=True, capture_output=True, text=True, timeout=10)
        self.assertEqual(result.stdout.strip(), 'io.p2wlan.mc.fabric.game.v1_20_1.fixture.GameProbe')
        with self.assertRaisesRegex(VerificationError, 'fixture_relocation_game_version'):
            fixture.build([self.classes], self.metadata, '1.20.2')

    def test_only_fixture_metadata_and_bounded_class_inventory_are_admitted(self):
        self.compile_probe()
        with patch.object(fixture, 'MAX_CLASSES', 1), self.assertRaisesRegex(VerificationError, 'fixture_relocation_capacity'):
            fixture.build([self.classes], self.metadata)
        self.metadata.write_text('{}')
        with self.assertRaisesRegex(VerificationError, 'fixture_relocation_metadata'):
            fixture.build([self.classes], self.metadata)

    def test_old_forge_fixture_preserves_metadata_and_executes_relocated_game_references(self):
        self.compile_probe('forge')
        metadata = "modLoader=\"javafml\"\nloaderVersion=\"[47,48)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"p2wlan_local_game_test\"\nversion=\"1\"\n"
        self.metadata.write_text(metadata)
        payload, count = fixture.build([self.classes], self.metadata, '1.20.1', 'forge', self.pack_metadata)
        self.assertEqual(count, 2)
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            self.assertEqual(archive.read('META-INF/mods.toml').decode(), metadata)
            self.assertEqual(archive.read('pack.mcmeta'), self.pack_metadata.read_bytes())
            self.assertNotIn('fabric.mod.json', archive.namelist())
            self.assertIn('io/p2wlan/mc/game/v1_20_1/fixture/GameProbe.class', archive.namelist())
            self.assertNotIn('io/p2wlan/mc/game/fixture/GameProbe.class', archive.namelist())
        output = self.root / 'forge-fixture.jar'; fixture.publish(output, payload)
        result = subprocess.run([shutil.which('java'), '-cp', str(output), 'io.p2wlan.mc.forge.fixture.Entry'],
                                check=True, capture_output=True, text=True, timeout=10)
        self.assertEqual(result.stdout.strip(), 'io.p2wlan.mc.game.v1_20_1.fixture.GameProbe')
        for value in ('1.21.1', '1.20.2'):
            with self.assertRaises(VerificationError):
                fixture.build([self.classes], self.metadata, value, 'forge', self.pack_metadata)
        for wrong in (metadata.replace('[47,48)', '[52,53)'), metadata.replace('p2wlan_local_game_test', 'p2wlan'),
                      'modLoader="javafml"\nloaderVersion="[47,48)"\nmods=["unowned"]\n'):
            self.metadata.write_text(wrong)
            with self.assertRaisesRegex(VerificationError, 'fixture_relocation_metadata'):
                fixture.build([self.classes], self.metadata, '1.20.1', 'forge', self.pack_metadata)

    def fabric_namespace(self, minecraft):
        segment = "v" + minecraft.replace(".", "_")
        self.compile_probe()
        payload, count = fixture.build([self.classes], self.metadata, minecraft)
        self.assertEqual(count, 2)
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            self.assertIn(f'io/p2wlan/mc/fabric/game/{segment}/fixture/GameProbe.class', archive.namelist())
            self.assertNotIn('io/p2wlan/mc/fabric/game/fixture/GameProbe.class', archive.namelist())
            self.assertNotIn('io/p2wlan/mc/fabric/game/v1_20_1/fixture/GameProbe.class', archive.namelist())
        output = self.root / f'fabric-{minecraft}-fixture.jar'; fixture.publish(output, payload)
        result = subprocess.run([shutil.which('java'), '-Xverify:all', '-cp', str(output), 'io.p2wlan.mc.fabric.fixture.Entry'],
                                check=True, capture_output=True, text=True, timeout=10)
        self.assertEqual(result.stdout.strip(), f'io.p2wlan.mc.fabric.game.{segment}.fixture.GameProbe')
        self.assertEqual(fixture.registry.sdk_input(minecraft, 'fabric').binding.game_namespace,
                         f'io.p2wlan.mc.fabric.game.{segment}')

    def test_fabric_1182_fixture_executes_only_the_exact_development_namespace(self):
        self.fabric_namespace('1.18.2')

    def test_fabric_1165_fixture_executes_only_the_exact_development_namespace(self):
        self.fabric_namespace('1.16.5')

    def chat_resource_contract(self, minecraft):
        chat_refmap = fixture.CHAT_REFMAPS[minecraft]
        self.compile_probe()
        metadata = json.loads(self.metadata.read_text())
        metadata['mixins'] = [{'config': fixture.CHAT_CONFIG, 'environment': 'client'}]
        self.metadata.write_text(json.dumps(metadata))
        config = self.root / fixture.CHAT_CONFIG
        config.write_text(json.dumps({'required': True, 'package': 'io.p2wlan.mc.fabric.fixture.mixin',
            'compatibilityLevel': 'JAVA_17', 'refmap': chat_refmap,
            'client': ['GameMessageMixin'], 'injectors': {'defaultRequire': 1}}))
        mapping = {'io/p2wlan/mc/fabric/fixture/mixin/GameMessageMixin': {
            'handleChat': 'Lnet/minecraft/class_634;method_11121(Lnet/minecraft/class_2635;)V'}}
        refmap = self.root / chat_refmap
        refmap.write_text(json.dumps({'mappings': mapping, 'data': {'named:intermediary': mapping}}))
        resources = [(fixture.CHAT_CONFIG, config)]
        server, _ = fixture.build([self.classes], self.metadata, minecraft, resources=resources)
        with zipfile.ZipFile(io.BytesIO(server)) as archive:
            self.assertEqual(archive.read(fixture.CHAT_CONFIG), config.read_bytes())
            self.assertNotIn(chat_refmap, archive.namelist())
        with self.assertRaisesRegex(VerificationError, 'resource_contract'):
            fixture.build([self.classes], self.metadata, minecraft)
        for name, package in [('LocalGameClient', 'io.p2wlan.mc.fabric.fixture'),
                              ('GameMessageMixin', 'io.p2wlan.mc.fabric.fixture.mixin')]:
            path = self.root / (name + '.java')
            path.write_text(f'package {package}; public class {name} {{}}')
            subprocess.run([shutil.which('javac'), '--release', '17', '-d', str(self.classes), str(path)],
                           check=True, capture_output=True, timeout=20)
        resources += [(chat_refmap, refmap)]
        client, _ = fixture.build([self.classes], self.metadata, minecraft, resources=resources)
        with zipfile.ZipFile(io.BytesIO(client)) as archive:
            self.assertEqual(archive.read(chat_refmap), refmap.read_bytes())
        for invalid in (resources[:1], resources + resources[:1], [('arbitrary.json', config)],
                        [(fixture.CHAT_CONFIG, refmap), (chat_refmap, refmap)]):
            with self.subTest(invalid=invalid), self.assertRaisesRegex(VerificationError, 'resource_'):
                fixture.build([self.classes], self.metadata, minecraft, resources=invalid)
        original = refmap.read_bytes()
        refmap.write_bytes(original.replace(b'method_11121', b'method_11108'))
        with self.assertRaisesRegex(VerificationError, 'resource_contract'):
            fixture.build([self.classes], self.metadata, minecraft, resources=resources)
        refmap.write_bytes(original)
        with self.assertRaisesRegex(VerificationError, 'resource_unexpected'):
            fixture.build([self.classes], self.metadata, '1.20.1', resources=resources)

    def test_1182_chat_resources_follow_the_exact_client_class_inventory(self):
        self.chat_resource_contract('1.18.2')

    def test_1165_chat_resources_follow_the_exact_client_class_inventory(self):
        self.chat_resource_contract('1.16.5')

    def test_forge_1182_fixture_requires_exact_metadata_and_executes_the_version_namespace(self):
        self.compile_probe('forge')
        metadata = 'modLoader="javafml"\nloaderVersion="[40,41)"\nlicense="MIT"\n[[mods]]\nmodId="p2wlan_integrated_test"\nversion="1"\n'
        self.metadata.write_text(metadata)
        pack = b'{"pack":{"description":"P2WLAN isolated gameplay fixture","pack_format":8}}'
        self.pack_metadata.write_bytes(pack)
        output = self.root / 'forge18-fixture.jar'
        arguments = ['fixture_relocation.py', '--classes', str(self.classes), '--metadata', str(self.metadata),
                     '--pack-metadata', str(self.pack_metadata), '--output', str(output), '--minecraft', '1.18.2', '--loader', 'forge']
        with patch('sys.argv', arguments), patch('sys.stdout', new=io.StringIO()) as status:
            fixture.main()
        self.assertIn('PASS installed fixture relocation: forge 1.18.2 2 classes', status.getvalue())
        with zipfile.ZipFile(output) as archive:
            self.assertEqual(archive.read('META-INF/mods.toml').decode(), metadata)
            self.assertEqual(archive.read('pack.mcmeta'), pack)
            self.assertIn('io/p2wlan/mc/game/v1_18_2/fixture/GameProbe.class', archive.namelist())
            self.assertNotIn('io/p2wlan/mc/game/v1_20_1/fixture/GameProbe.class', archive.namelist())
        result = subprocess.run([shutil.which('java'), '-Xverify:all', '-cp', str(output), 'io.p2wlan.mc.forge.fixture.Entry'],
                                check=True, capture_output=True, text=True, timeout=10)
        self.assertEqual(result.stdout.strip(), 'io.p2wlan.mc.game.v1_18_2.fixture.GameProbe')
        self.assertEqual(fixture.build([self.classes], self.metadata, '1.18.2', 'forge', self.pack_metadata)[0], output.read_bytes())
        self.metadata.write_text(metadata.replace('[40,41)', '[47,48)'))
        with self.assertRaisesRegex(VerificationError, 'fixture_relocation_metadata'):
            fixture.build([self.classes], self.metadata, '1.18.2', 'forge', self.pack_metadata)
        self.metadata.write_text(metadata)
        self.pack_metadata.write_bytes(pack.replace(b':8', b':15'))
        with self.assertRaisesRegex(VerificationError, 'fixture_relocation_pack_metadata'):
            fixture.build([self.classes], self.metadata, '1.18.2', 'forge', self.pack_metadata)
        for minecraft, loader in [('1.18.1', 'forge'), ('1.16.4', 'fabric'), ('1.18.2', 'neoforge')]:
            with self.subTest(minecraft=minecraft, loader=loader), self.assertRaises(VerificationError):
                fixture.build([self.classes], self.metadata, minecraft, loader, self.pack_metadata)

    def test_pack_metadata_is_forge_only_required_and_exact_typed_json(self):
        with self.assertRaisesRegex(VerificationError, 'pack_metadata_required'):
            fixture.build([self.classes], self.metadata, '1.20.1', 'forge')
        with self.assertRaisesRegex(VerificationError, 'pack_metadata_unexpected'):
            fixture.build([self.classes], self.metadata, '1.20.1', 'fabric', self.pack_metadata)
        expected = self.pack_metadata.read_bytes()
        self.assertEqual(fixture.forge_pack_metadata(expected), expected)
        values = [b'{}', expected.decode().encode('utf-16'), expected.replace(b'15', b'15.0'), expected.replace(b'15', b'true'),
                  expected.replace(b'15', b'48'), expected.replace(b'"pack_format": 15', b'"pack_format": 15, "pack_format": 15'),
                  expected.replace(b'P2WLAN isolated gameplay fixture', b'other pack'),
                  expected.replace(b'"pack_format": 15', b'"pack_format": 15, "extra": 1')]
        for value in values:
            with self.subTest(value=value), self.assertRaisesRegex(VerificationError, 'fixture_relocation_pack_metadata'):
                fixture.forge_pack_metadata(value)


if __name__ == '__main__':
    unittest.main()
