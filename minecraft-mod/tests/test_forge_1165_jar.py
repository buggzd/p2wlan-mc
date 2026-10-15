"""Forge 1.16.5 archive policy mutations; synthetic bytes, no gameplay claim."""
from copy import deepcopy
import io
import json
from pathlib import Path
import stat
import struct
import sys
import unittest
import warnings
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import verify_forge_1165_jar as verify
import test_legacy_gradle as legacy_fixtures
import test_universal_jar as universal_fixtures
from test_mod_jar import bytecode, jar


class Forge1165JarTests(unittest.TestCase):
    def fixture(self):
        top = {name: bytecode(61) for name in verify.CLASSES}
        top['META-INF/mods.toml'] = '''modLoader="javafml"
loaderVersion="[36,)"
[[mods]]
modId="p2wlan"
version="0.1.0"
[[dependencies.p2wlan]]
modId="forge"
mandatory=true
versionRange="[36.2.42]"
ordering="NONE"
side="BOTH"
[[dependencies.p2wlan]]
modId="minecraft"
mandatory=true
versionRange="[1.16.5]"
ordering="NONE"
side="BOTH"
'''
        top['META-INF/MANIFEST.MF'] = 'Manifest-Version: 1.0\r\nMixinConfigs: ' + verify.SERVER_CONFIG + ',' + verify.CLIENT_CONFIG + '\r\n\r\n'
        top['pack.mcmeta'] = json.dumps({'pack': {'description': 'P2WLAN', 'pack_format': 6}})
        top[verify.SERVER_CONFIG] = json.dumps(verify.MIXIN_CONFIG)
        top[verify.CLIENT_CONFIG] = json.dumps(verify.CLIENT_MIXIN_CONFIG)
        for name, targets in ((verify.SERVER_REFMAP, verify.SERVER_TARGETS), (verify.CLIENT_REFMAP, verify.CLIENT_TARGETS)):
            top[name] = json.dumps({'mappings': targets, 'data': {'searge': targets}})
        for owner, field, original in verify.SHADOW_FIELDS:
            top['io/p2wlan/mc/game/' + owner + '.class'] = legacy_fixtures.LegacyGradleTests.field_fixture(field, original)
        top[verify.ACTIVATION_MIXIN] = universal_fixtures.bytecode(verify.ACTIVATION_MIXIN,
                marker=' '.join(token.decode() for token in sorted(verify.ACTIVATION_TOKENS)), fields=('activationHandler',))
        return top

    def check(self, top, development=True):
        return verify.verify(io.BytesIO(jar(top)), development)

    def shaped_archive(self, top, selected=None, mode=None, compression=zipfile.ZIP_DEFLATED):
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as archive:
            for name, value in top.items():
                item = zipfile.ZipInfo(name)
                item.create_system = 3
                item.compress_type = compression if name == selected else zipfile.ZIP_DEFLATED
                item.external_attr = ((mode if mode is not None and name == selected else stat.S_IFREG | 0o644) << 16)
                archive.writestr(item, value)
        return output.getvalue()

    def encrypted_archive(self, flags):
        raw = self.shaped_archive(self.fixture())
        selected = 'META-INF/MANIFEST.MF'
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            start = archive.start_dir
            local = archive.getinfo(selected).header_offset
        output = bytearray(raw)
        while output[start:start + 4] == b'PK\x01\x02':
            name_size, extra, comment = struct.unpack_from('<HHH', output, start + 28)
            if output[start + 46:start + 46 + name_size].decode() == selected:
                struct.pack_into('<H', output, start + 8, flags)
                struct.pack_into('<H', output, local + 6, flags)
                return bytes(output)
            start += 46 + name_size + extra + comment
        self.fail('selected central-directory entry missing')

    def test_exact_raw_sdk_scope_and_no_nested_runtime_owners(self):
        result = self.check(self.fixture())
        self.assertEqual(result, {'schema': 1, 'minecraft': '1.16.5', 'forge': '36.2.42', 'java': 17,
                                 'shape': 'raw-sdk-leaf', 'classes': 62, 'game_classes': 54,
                                 'verification': 'sdk-class/refmap-contract'})
        self.assertEqual((len(verify.CLASSES), len(verify.GAME_TYPES), len(verify.DEDICATED_CLASSES)), (62, 54, 14))
        for name in ('META-INF/jarjar/common.jar', 'p2wlan/native/windows-x86_64/manifest.properties'):
            top = self.fixture(); top[name] = b'unowned runtime payload'
            with self.subTest(name=name), self.assertRaisesRegex(verify.VerificationError, 'forge1165_raw_sdk_nested_payload'):
                self.check(top)

    def test_pins_manifest_and_pack_metadata_are_exact(self):
        for before, after in [('javafml', 'unknown'), ('[36,)', '[36,37)'), ('[36.2.42]', '[36,)'), ('[1.16.5]', '[1.16,)')]:
            top = self.fixture(); top['META-INF/mods.toml'] = top['META-INF/mods.toml'].replace(before, after)
            with self.subTest(before=before), self.assertRaisesRegex(verify.VerificationError, 'forge1165_mod_contract'): self.check(top)
        top = self.fixture(); top['META-INF/MANIFEST.MF'] = 'Manifest-Version: 1.0\r\n'
        with self.assertRaisesRegex(verify.VerificationError, 'forge1165_mixin_manifest'): self.check(top)
        for pack in ({}, {'pack': {'description': 'P2WLAN', 'pack_format': 6.0}}, {'pack': {'description': 'P2WLAN', 'pack_format': 15}}):
            top = self.fixture(); top['pack.mcmeta'] = json.dumps(pack)
            with self.subTest(pack=pack), self.assertRaisesRegex(verify.VerificationError, 'forge1165_pack_contract'): self.check(top)

    def test_mixin_inventory_and_marker_are_exact(self):
        for name in (verify.SERVER_CONFIG, verify.CLIENT_CONFIG):
            for mutation in ({'required': 1}, {'injectors': {'defaultRequire': 1.0}}, {'minVersion': '0.8.3'}):
                top = self.fixture(); top[name] = json.dumps(json.loads(top[name]) | mutation)
                with self.subTest(name=name, mutation=mutation), self.assertRaisesRegex(verify.VerificationError, 'forge1165_mixin_contract'): self.check(top)
        top = self.fixture(); config = json.loads(top[verify.CLIENT_CONFIG]); config['client'].remove('ClientConnectionMixin')
        top[verify.CLIENT_CONFIG] = json.dumps(config)
        with self.assertRaisesRegex(verify.VerificationError, 'forge1165_mixin_contract'): self.check(top)

    def test_refmaps_require_actual_old_selectors_owners_and_descriptors(self):
        for name, targets in ((verify.SERVER_REFMAP, verify.SERVER_TARGETS), (verify.CLIENT_REFMAP, verify.CLIENT_TARGETS)):
            for owner, methods in targets.items():
                for selector in methods:
                    for value in (None, 'Lnet/minecraft/Unrelated;wrong()V'):
                        changed = deepcopy(targets)
                        if value is None: del changed[owner][selector]
                        else: changed[owner][selector] = value
                        top = self.fixture(); top[name] = json.dumps({'mappings': changed, 'data': {'searge': changed}})
                        with self.subTest(name=name, owner=owner, selector=selector), self.assertRaisesRegex(verify.VerificationError, 'forge1165_refmap_contract'):
                            self.check(top)
        top = self.fixture(); targets = deepcopy(verify.CLIENT_TARGETS)
        targets['io/p2wlan/mc/game/mixin/ClientConnectionMixin'] = {'connect': 'unverified()V'}
        top[verify.CLIENT_REFMAP] = json.dumps({'mappings': targets, 'data': {'searge': targets}})
        with self.assertRaisesRegex(verify.VerificationError, 'forge1165_refmap_contract'): self.check(top)

    def test_actual_shadow_declarations_are_required(self):
        for owner, field, original in verify.SHADOW_FIELDS:
            for indices in ((2,), (1, 2)):
                top = self.fixture(); top['io/p2wlan/mc/game/' + owner + '.class'] = legacy_fixtures.LegacyGradleTests.field_fixture(field, original, indices)
                with self.subTest(owner=owner, indices=indices), self.assertRaisesRegex(verify.VerificationError, 'forge1165_shadow_srg'): self.check(top)

    def test_client_activation_glue_cannot_be_a_marker_or_unmapped_getter(self):
        top = self.fixture(); top[verify.ACTIVATION_MIXIN] = bytecode(61)
        with self.assertRaises(verify.VerificationError): self.check(top)
        for token in verify.ACTIVATION_TOKENS:
            top = self.fixture(); top[verify.ACTIVATION_MIXIN] = top[verify.ACTIVATION_MIXIN].replace(token, b'x' * len(token))
            with self.subTest(token=token), self.assertRaisesRegex(verify.VerificationError, 'forge1165_client_activation_contract'):
                self.check(top)

    def test_unused_activation_constant_cannot_replace_the_forge_shadow_declaration(self):
        marker = ' '.join(token.decode() for token in sorted(verify.ACTIVATION_TOKENS))
        for fields in (('mappedActivationField',), ('activationHandler', 'extraOwner')):
            top = self.fixture(); top[verify.ACTIVATION_MIXIN] = universal_fixtures.bytecode(verify.ACTIVATION_MIXIN,
                    marker=marker, fields=fields)
            with self.subTest(fields=fields), self.assertRaisesRegex(verify.VerificationError, 'forge1165_client_activation_contract'):
                self.check(top)

    def test_game_and_delegate_inventory_bytecode_and_dedicated_separation(self):
        for member in verify.CLASSES:
            for mutation in ('missing', 'java21'):
                top = self.fixture()
                if mutation == 'missing': del top[member]
                else: top[member] = bytecode(65)
                with self.subTest(member=member, mutation=mutation), self.assertRaises(verify.VerificationError): self.check(top)
        for member in verify.DEDICATED_CLASSES:
            top = self.fixture(); top[member] += b'net/minecraft/client/Minecraft'
            with self.subTest(member=member), self.assertRaisesRegex(verify.VerificationError, 'mod_dedicated_client_reference'): self.check(top)
        for member in ('io/p2wlan/mc/game/fixture/GameFixtureApi.class', 'io/p2wlan/mc/game/server/mixin/ServerConfigurationMixin.class',
                       'io/p2wlan/mc/forge/legacy/ForgeServerEvents.class', 'META-INF/versions/17/io/p2wlan/mc/game/GameText.class'):
            top = self.fixture(); top[member] = bytecode(61)
            with self.subTest(member=member), self.assertRaisesRegex(verify.VerificationError, 'forge1165_class_inventory'): self.check(top)

    def test_nonclass_resources_form_the_exact_actual_sdk_inventory(self):
        self.assertEqual(set(self.fixture()) - verify.CLASSES, set(verify.RESOURCE_LIMITS))
        for name in ('payload.txt', 'logs/runtime.log', 'helper.exe', 'META-INF/services/any.Provider',
                     'META-INF/LICENSE', 'p2wlan/native/unknown.bin', 'nested.jar'):
            top = self.fixture(); top[name] = b'unknown payload'
            reason = 'forge1165_raw_sdk_nested_payload' if name.startswith('p2wlan/native/') or name.endswith('.jar') \
                else 'forge1165_resource_inventory'
            with self.subTest(name=name), self.assertRaisesRegex(verify.VerificationError, reason): self.check(top)
        for name in verify.RESOURCE_LIMITS:
            top = self.fixture(); del top[name]
            with self.subTest(missing=name), self.assertRaisesRegex(verify.VerificationError, 'forge1165_resource_inventory'): self.check(top)

    def test_zip_shape_rejects_duplicates_symlinks_devices_encryption_and_unapproved_codecs(self):
        selected = 'META-INF/MANIFEST.MF'
        output = io.BytesIO(jar(self.fixture()))
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(output, 'a') as archive: archive.writestr(selected, 'duplicate')
        with self.assertRaisesRegex(verify.VerificationError, 'mod_archive_entries'): verify.verify(io.BytesIO(output.getvalue()))
        for mode in (stat.S_IFLNK, stat.S_IFDIR, stat.S_IFIFO, stat.S_IFCHR, stat.S_IFSOCK):
            with self.subTest(mode=mode), self.assertRaisesRegex(verify.VerificationError, 'forge1165_archive_entry_invalid'):
                verify.verify(io.BytesIO(self.shaped_archive(self.fixture(), selected, mode | 0o644)))
        for flags in (1, 0x40, 0x41):
            with self.subTest(flags=flags), self.assertRaisesRegex(verify.VerificationError, 'forge1165_archive_entry_invalid'):
                verify.verify(io.BytesIO(self.encrypted_archive(flags)))
        with self.assertRaisesRegex(verify.VerificationError, 'forge1165_archive_entry_invalid'):
            verify.verify(io.BytesIO(self.shaped_archive(self.fixture(), selected, compression=zipfile.ZIP_BZIP2)))

    def test_member_and_total_capacity_and_directory_shape_are_bounded_before_reads(self):
        for name, maximum in [*verify.RESOURCE_LIMITS.items(), (next(iter(verify.CLASSES)), 1024 * 1024)]:
            for size in (0, maximum + 1):
                top = self.fixture(); top[name] = b'x' * size
                with self.subTest(name=name, size=size), self.assertRaisesRegex(verify.VerificationError, 'forge1165_archive_member_capacity'):
                    self.check(top)
        top = self.fixture()
        for name in sorted(verify.CLASSES)[:33]: top[name] = b'x' * (1024 * 1024)
        with self.assertRaisesRegex(verify.VerificationError, 'forge1165_archive_member_capacity'): self.check(top)
        for name, value in (('unexpected/', b''), ('io/', b'payload')):
            top = self.fixture(); top[name] = value
            with self.subTest(name=name), self.assertRaisesRegex(verify.VerificationError, 'forge1165_archive_entry_invalid'):
                self.check(top)
        top = self.fixture(); top['io/'] = b''
        self.assertEqual(self.check(top)['classes'], 62)


if __name__ == '__main__':
    unittest.main()
