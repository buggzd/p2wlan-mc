"""Legacy Fabric inputs retain the shared resource and exact SDK contracts."""
from copy import deepcopy
from contextlib import redirect_stderr
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import test_mod_jar as modern_tests
import verify_fabric_legacy_jar as legacy
from artifact_checks import VerificationError
from jarjar_artifact import WORLD_COMMON


class LegacyFabricJarTests(unittest.TestCase):
    def setUp(self):
        binding = modern_tests.jna_fixture_binding()
        binding.start()
        self.addCleanup(binding.stop)

    def fixture(self):
        top, info, common, native = modern_tests.ModJarTests().fixture()
        top.pop(legacy.MODERN_SERVER_MIXIN)
        for name in (modern_tests.verify.MIXIN_CONFIG['refmap'], modern_tests.verify.CLIENT_MIXIN_CONFIG['refmap']):
            del top[name]
        info['depends'] = deepcopy(legacy.DEPENDS)
        top['fabric.mod.json'] = json.dumps(info)
        top['p2wlan.mixins.json'] = json.dumps(legacy.MIXIN_CONFIG)
        top['p2wlan.client.mixins.json'] = json.dumps(legacy.CLIENT_MIXIN_CONFIG)
        top[legacy.SERVER_REFMAP] = json.dumps(legacy.REFMAP)
        top[legacy.CLIENT_REFMAP] = json.dumps(legacy.CLIENT_REFMAP_VALUE)
        for name in (legacy.WORLD, legacy.SERVER, legacy.CLIENT_EVENTS, legacy.LOGIN_MIXIN, legacy.MAIN[:-6] + '$ServerEvents.class',
                     legacy.MAIN[:-6] + '$Binding.class', legacy.WORLD[:-6] + '$IntegratedHooks.class',
                     'io/p2wlan/mc/bootstrap/UniversalEnvironment.class', 'io/p2wlan/mc/bootstrap/BootstrapFailure.class',
                     'io/p2wlan/mc/game/client/ClientPlatform.class', 'io/p2wlan/mc/game/client/GameUi.class',
                     'io/p2wlan/mc/game/client/connect/GameLogin.class'):
            top[name] = modern_tests.bytecode(61)
        top['io/p2wlan/mc/game/mixin/MultiplayerListMixin.class'] += b'(Lnet/minecraft/class_310;IIIII)V'
        common.update({name: modern_tests.bytecode(61) for name in WORLD_COMMON})
        top['META-INF/jars/common.jar'] = modern_tests.jar(common)
        return top, info, common, native

    def check(self, top):
        return legacy.verify(io.BytesIO(modern_tests.jar(top)), True)

    def test_complete_legacy_input_reuses_shared_native_and_jna_policy(self):
        top, _, _, _ = self.fixture()
        self.assertEqual(self.check(top)[0]['verification-platform'], 'macos-aarch64')
        top['META-INF/jars/jna.jar'] = modern_tests.jar(modern_tests.JNA_FIXTURE | {'com/sun/jna/Native.class': b'tampered'})
        with self.assertRaisesRegex(VerificationError, 'mod_jna_payload'):
            self.check(top)

    def test_loader_game_java_and_api_pins_are_exact(self):
        for field, value in [('minecraft', '1.21.1'), ('minecraft', ['1.20.1', '1.21.1']), ('java', '>=21'),
                             ('fabricloader', '>=0.16.14'), ('fabric-api', '*')]:
            top, info, _, _ = self.fixture()
            info['depends'][field] = value
            top['fabric.mod.json'] = json.dumps(info)
            with self.subTest(field=field, value=value), self.assertRaisesRegex(VerificationError, 'legacy_fabric_mod_contract'):
                self.check(top)
        top, info, _, _ = self.fixture()
        info['schemaVersion'] = True
        top['fabric.mod.json'] = json.dumps(info)
        with self.assertRaisesRegex(VerificationError, 'legacy_fabric_mod_contract'):
            self.check(top)

    def test_refmaps_require_every_real_loom_binding(self):
        for path, expected in [(legacy.SERVER_REFMAP, legacy.REFMAP), (legacy.CLIENT_REFMAP, legacy.CLIENT_REFMAP_VALUE)]:
            for changed in [{}, expected | {'data': {}}, expected | {'mappings': {}}, expected | {'extra': True}]:
                top, _, _, _ = self.fixture()
                top[path] = json.dumps(changed)
                with self.subTest(path=path), self.assertRaisesRegex(VerificationError, 'legacy_fabric_mixin_refmap'):
                    self.check(top)

    def test_legacy_login_bindings_do_not_admit_modern_or_unmapped_targets(self):
        for owner, targets in legacy.SERVER_MAPPING.items():
            for key in targets:
                for change in (None, 'Lnet/minecraft/Unrelated;wrong()V'):
                    mapped = deepcopy(legacy.SERVER_MAPPING)
                    if change is None:
                        del mapped[owner][key]
                    else:
                        mapped[owner][key] = change
                    top, _, _, _ = self.fixture()
                    top[legacy.SERVER_REFMAP] = json.dumps({'mappings': mapped, 'data': {'named:intermediary': mapped}})
                    with self.subTest(owner=owner, key=key, change=change), self.assertRaisesRegex(VerificationError, 'legacy_fabric_mixin_refmap'):
                        self.check(top)
        top, _, _, _ = self.fixture()
        top[legacy.SERVER_REFMAP] = json.dumps(modern_tests.verify.REFMAP)
        with self.assertRaisesRegex(VerificationError, 'legacy_fabric_mixin_refmap'):
            self.check(top)

    def test_server_login_mixins_are_required_java17_dedicated_classes(self):
        for member in legacy.SERVER_MIXINS:
            for change, reason in [(None, 'mod_archive_member_invalid'), (modern_tests.bytecode(65), 'mod_class_version'),
                                   (modern_tests.bytecode(61) + b'io/p2wlan/mc/game/client/', 'mod_dedicated_client_reference')]:
                top, _, _, _ = self.fixture()
                if change is None:
                    del top[member]
                else:
                    top[member] = change
                with self.subTest(member=member, reason=reason), self.assertRaisesRegex(VerificationError, reason):
                    self.check(top)
        for classes in [['ServerConnectionMixin'], legacy.MIXIN_CONFIG['mixins'] + ['UnknownMixin']]:
            top, _, _, _ = self.fixture()
            top['p2wlan.mixins.json'] = json.dumps(legacy.MIXIN_CONFIG | {'mixins': classes})
            with self.assertRaisesRegex(VerificationError, 'legacy_fabric_mixin_contract'):
                self.check(top)

    def test_constructor_and_java17_cannot_be_replaced_by_modern_outputs(self):
        top, _, _, _ = self.fixture()
        top['io/p2wlan/mc/game/mixin/MultiplayerListMixin.class'] = modern_tests.bytecode(61) + b'(Lnet/minecraft/class_310;IIII)V'
        with self.assertRaisesRegex(VerificationError, 'legacy_fabric_list_constructor'):
            self.check(top)
        for name in [legacy.SERVER, legacy.CLIENT_EVENTS, 'io/p2wlan/mc/game/client/Extra.class']:
            top, _, _, _ = self.fixture()
            top[name] = modern_tests.bytecode(65)
            with self.subTest(name=name), self.assertRaisesRegex(VerificationError, 'mod_class_version'):
                self.check(top)

    def test_unselected_delegates_and_extra_resource_owners_are_rejected(self):
        for name in ['io/p2wlan/mc/fabric/current/FabricServerEvents.class', 'com/sun/jna/Native.class',
                     'io/p2wlan/mc/common/WorldSessions.class', 'io/p2wlan/mc/game/fixture/GameClientChecks.class',
                     legacy.MODERN_SERVER_MIXIN]:
            top, _, _, _ = self.fixture()
            top[name] = modern_tests.bytecode(61)
            with self.subTest(name=name), self.assertRaises(VerificationError):
                self.check(top)
        top, _, _, _ = self.fixture()
        top['META-INF/jars/unlisted.jar'] = modern_tests.jar({})
        with self.assertRaisesRegex(VerificationError, 'legacy_fabric_unlisted_nested_resource'):
            self.check(top)

    def test_fabric_api_login_mixin_is_required_without_an_extra_mc_refmap_entry(self):
        top, _, _, _ = self.fixture()
        self.check(top)
        del top[legacy.LOGIN_MIXIN]
        with self.assertRaisesRegex(VerificationError, 'mod_archive_member_invalid'):
            self.check(top)
        top, _, _, _ = self.fixture()
        top[legacy.LOGIN_MIXIN] = modern_tests.bytecode(65)
        with self.assertRaisesRegex(VerificationError, 'mod_class_version'):
            self.check(top)
        top, _, _, _ = self.fixture()
        mapped = json.loads(top[legacy.CLIENT_REFMAP])
        mapped['mappings'][legacy.LOGIN_MIXIN.removesuffix('.class')] = {}
        top[legacy.CLIENT_REFMAP] = json.dumps(mapped)
        with self.assertRaisesRegex(VerificationError, 'legacy_fabric_mixin_refmap'):
            self.check(top)

    def test_dedicated_sources_and_world_owner_are_required(self):
        for name in [legacy.WORLD, legacy.SERVER, legacy.MAIN[:-6] + '$ServerEvents.class']:
            top, _, _, _ = self.fixture()
            del top[name]
            with self.subTest(name=name), self.assertRaises(VerificationError):
                self.check(top)
        top, _, common, _ = self.fixture()
        del common['io/p2wlan/mc/common/WorldSessions$World.class']
        top['META-INF/jars/common.jar'] = modern_tests.jar(common)
        with self.assertRaises(VerificationError):
            self.check(top)
        top, _, _, _ = self.fixture()
        top[legacy.SERVER] += b'net/fabricmc/fabric/api/client/'
        with self.assertRaisesRegex(VerificationError, 'mod_dedicated_client_reference'):
            self.check(top)

    def test_required_mixin_flags_do_not_accept_numeric_bool_aliases(self):
        for field, value in [('required', 1), ('required', False), ('injectors', {'defaultRequire': 1.0})]:
            top, _, _, _ = self.fixture()
            config = deepcopy(legacy.MIXIN_CONFIG)
            config[field] = value
            top['p2wlan.mixins.json'] = json.dumps(config)
            with self.subTest(field=field), self.assertRaisesRegex(VerificationError, 'legacy_fabric_mixin_contract'):
                self.check(top)

    def test_cli_checks_the_same_snapshot_before_source_binding(self):
        top, _, _, _ = self.fixture()
        snapshot = modern_tests.jar(top)
        with patch.object(sys, 'argv', ['verify_fabric_legacy_jar.py', 'verify', 'archive.jar', '--development']), \
                patch.object(legacy, 'bounded_file', return_value=snapshot) as bounded, \
                patch.object(legacy, 'bind_sources', side_effect=VerificationError('mod_native_sources_changed_rebuild_bundle')) as binding:
            with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as failed:
                legacy.main()
            self.assertEqual(failed.exception.code, 1)
            bounded.assert_called_once()
            self.assertEqual(binding.call_args.args[1], True)


if __name__ == '__main__':
    unittest.main()
