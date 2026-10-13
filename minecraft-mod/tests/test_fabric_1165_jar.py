"""Fabric 1.16.5 archive guards use the actual SDK map and owned class set."""
from copy import deepcopy
import io
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import test_mod_jar as fixtures
import universal_jar as universal
import verify_fabric_1165_jar as adapter
import test_legacy_gradle as legacy_fixtures
from artifact_checks import VerificationError
from jarjar_artifact import WORLD_COMMON


class Fabric1165JarTests(unittest.TestCase):
    def setUp(self):
        binding = fixtures.jna_fixture_binding(); binding.start(); self.addCleanup(binding.stop)

    def fixture(self):
        top, info, common, _ = fixtures.ModJarTests().fixture()
        for name in list(top):
            if name.endswith('.class') or name.endswith('-refmap.json'): del top[name]
        info['depends'] = deepcopy(adapter.DEPENDS)
        info['mixins'] = deepcopy(adapter.MIXINS)
        top['fabric.mod.json'] = json.dumps(info)
        top['p2wlan.mixins.json'] = json.dumps(adapter.MIXIN_CONFIG)
        top['p2wlan.client.mixins.json'] = json.dumps(adapter.CLIENT_MIXIN_CONFIG)
        top[adapter.SERVER_REFMAP] = json.dumps(adapter.REFMAP)
        top[adapter.CLIENT_REFMAP] = json.dumps(adapter.CLIENT_REFMAP_VALUE)
        loader = {adapter.MAIN, adapter.CLIENT, adapter.WORLD, adapter.SERVER, adapter.CLIENT_EVENTS,
                  adapter.MAIN[:-6] + '$Binding.class', adapter.MAIN[:-6] + '$ServerEvents.class',
                  adapter.WORLD[:-6] + '$IntegratedHooks.class'}
        extras = {adapter.LOGIN_MIXIN, 'io/p2wlan/mc/game/GameText.class',
                  'io/p2wlan/mc/game/client/GameUi$TooltipButton.class',
                  'io/p2wlan/mc/game/client/connect/MinecraftConnect$1.class',
                  'io/p2wlan/mc/game/mixin/LanNarrationMixin.class'}
        top.update({name: fixtures.bytecode(61) for name in universal.GAME_CLASSES | universal.BOOTSTRAP_CLASSES | loader | extras})
        for owner, field, original in adapter.SHADOW_FIELDS:
            top['io/p2wlan/mc/game/' + owner + '.class'] = legacy_fixtures.LegacyGradleTests.field_fixture(field, original)
        top['io/p2wlan/mc/game/mixin/MultiplayerListMixin.class'] += b'(Lnet/minecraft/class_310;IIIII)V'
        common.update({name: fixtures.bytecode(61) for name in WORLD_COMMON})
        top['META-INF/jars/common.jar'] = fixtures.jar(common)
        return top, info

    def check(self, top):
        return adapter.verify(io.BytesIO(fixtures.jar(top)), True)

    def test_complete_input_uses_exact_catalog_and_shared_portable_resources(self):
        top, _ = self.fixture()
        self.assertEqual(self.check(top)[0]['verification-platform'], 'macos-aarch64')
        top['META-INF/jars/jna.jar'] = fixtures.jar(fixtures.JNA_FIXTURE | {'com/sun/jna/Native.class': b'tampered'})
        with self.assertRaisesRegex(VerificationError, 'mod_jna_payload'): self.check(top)

    def test_loader_game_java_and_api_pins_are_exact(self):
        for field, value in [('minecraft', '1.16'), ('minecraft', ['1.16.1', '1.16.5']), ('java', '>=21'),
                             ('fabricloader', '>=0.16.14'), ('fabric', '*')]:
            top, info = self.fixture(); info['depends'][field] = value; top['fabric.mod.json'] = json.dumps(info)
            with self.subTest(field=field), self.assertRaisesRegex(VerificationError, 'fabric1165_mod_contract'): self.check(top)
        top, info = self.fixture(); info['depends']['fabric-api'] = info['depends'].pop('fabric')
        top['fabric.mod.json'] = json.dumps(info)
        with self.assertRaisesRegex(VerificationError, 'fabric1165_mod_contract'): self.check(top)

    def test_exact_login_admission_and_client_mixin_maps_cannot_be_removed_or_redirected(self):
        for path, original in ((adapter.SERVER_REFMAP, adapter.REFMAP), (adapter.CLIENT_REFMAP, adapter.CLIENT_REFMAP_VALUE)):
            for owner, selectors in original['mappings'].items():
                for selector in selectors:
                    for target in (None, 'Lnet/minecraft/Unrelated;wrong()V'):
                        mapped = deepcopy(original['mappings'])
                        if target is None: del mapped[owner][selector]
                        else: mapped[owner][selector] = target
                        top, _ = self.fixture(); top[path] = json.dumps({'mappings': mapped, 'data': {'named:intermediary': mapped}})
                        with self.subTest(path=path, owner=owner, selector=selector), self.assertRaisesRegex(VerificationError, 'fabric1165_mixin_refmap'): self.check(top)
        # The old nonblocking bootstrap wraps the exact shared scope directly;
        # it does not claim the newer Vanilla connect-method injection.
        self.assertNotIn('io/p2wlan/mc/game/mixin/ClientConnectionMixin', adapter.CLIENT_MAPPING)

    def test_owned_catalog_rejects_missing_thin_api_and_extra_or_fixture_classes(self):
        for member in ('io/p2wlan/mc/game/GameText.class', 'io/p2wlan/mc/game/client/GameUi$TooltipButton.class',
                       'io/p2wlan/mc/game/client/connect/MinecraftConnect$1.class'):
            top, _ = self.fixture(); del top[member]
            with self.subTest(member=member), self.assertRaises(VerificationError): self.check(top)
        for member in ('io/p2wlan/mc/game/Unlisted.class', 'io/p2wlan/mc/game/fixture/Hidden.class',
                       adapter.MODERN_SERVER_MIXIN, 'io/p2wlan/mc/fabric/legacy/FabricServerEvents.class'):
            top, _ = self.fixture(); top[member] = fixtures.bytecode(61)
            with self.subTest(member=member), self.assertRaisesRegex(VerificationError, 'fabric1165_class_catalog'): self.check(top)

    def test_dedicated_classes_remain_java17_without_client_initialization(self):
        for member in (adapter.MAIN, adapter.SERVER, adapter.WORLD, adapter.WORLD_COMMANDS,
                       'io/p2wlan/mc/game/GameText.class', 'io/p2wlan/mc/game/server/GameCommandApi.class', *adapter.SERVER_MIXINS):
            for value, reason in ((fixtures.bytecode(65), 'mod_class_version'),
                                  (fixtures.bytecode(61) + b'io/p2wlan/mc/game/client/', 'mod_dedicated_client_reference')):
                top, _ = self.fixture(); top[member] = value
                with self.subTest(member=member, reason=reason), self.assertRaisesRegex(VerificationError, reason): self.check(top)

    def test_list_constructor_remains_the_verified_six_argument_old_sdk_shape(self):
        top, _ = self.fixture(); top['io/p2wlan/mc/game/mixin/MultiplayerListMixin.class'] = fixtures.bytecode(61)
        with self.assertRaisesRegex(VerificationError, 'fabric1165_list_constructor'): self.check(top)

    def test_remapped_shadow_fields_include_exact_original_login_connection(self):
        for owner, field, original in adapter.SHADOW_FIELDS:
            for indices in ((2,), (1, 2)):
                top, _ = self.fixture()
                name = 'io/p2wlan/mc/game/' + owner + '.class'
                top[name] = legacy_fixtures.LegacyGradleTests.field_fixture(field, original, indices)
                if owner == 'mixin/MultiplayerListMixin': top[name] += b'(Lnet/minecraft/class_310;IIIII)V'
                with self.subTest(owner=owner, indices=indices), self.assertRaisesRegex(VerificationError, 'fabric1165_shadow_intermediary'): self.check(top)


if __name__ == '__main__': unittest.main()
