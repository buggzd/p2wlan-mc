"""Exact current profile facts, immutable projections and source binding."""
from dataclasses import FrozenInstanceError, replace
from pathlib import Path
import sys
import tempfile
from types import MappingProxyType
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import minecraft_profiles as profiles


class MinecraftProfileTests(unittest.TestCase):
    def test_only_the_ten_exact_installation_pairs_are_published(self):
        expected = {
            ('1.20.1', 'fabric'): (17, 'fabric', 'io.p2wlan.mc.fabric.game.v1_20_1', '0.16.14', 'legacy_fabric'),
            ('1.20.1', 'neoforge'): (17, 'fml', 'io.p2wlan.mc.game.v1_20_1', '47.1.106', 'legacy_forge'),
            ('1.20.1', 'forge'): (17, 'fml', 'io.p2wlan.mc.game.v1_20_1', '47.3.2', 'legacy_forge'),
            ('1.21.1', 'fabric'): (21, 'fabric', 'io.p2wlan.mc.fabric.game', '0.16.14', 'fabric'),
            ('1.21.1', 'neoforge'): (21, 'fml', 'io.p2wlan.mc.game', '21.1.256', 'neoforge'),
            ('1.21.1', 'forge'): (21, 'fml', 'io.p2wlan.mc.game', '52.1.16', 'forge'),
            ('1.18.2', 'fabric'): (17, 'fabric', 'io.p2wlan.mc.fabric.game.v1_18_2', '0.16.14', 'fabric_1182'),
            ('1.18.2', 'forge'): (17, 'fml', 'io.p2wlan.mc.game.v1_18_2', '40.3.12', 'forge_1182'),
            ('1.16.5', 'fabric'): (17, 'fabric', 'io.p2wlan.mc.fabric.game.v1_16_5', '0.16.14', 'fabric_1165'),
            ('1.16.5', 'forge'): (17, 'fml', 'io.p2wlan.mc.game.v1_16_5', '36.2.42', 'forge_1165'),
        }
        self.assertEqual(set(profiles.PROFILES), set(expected))
        self.assertEqual(profiles.MINECRAFT_VERSIONS, ('1.16.5', '1.18.2', '1.20.1', '1.21.1'))
        self.assertEqual(profiles.LOADERS, ('fabric', 'neoforge', 'forge'))
        self.assertEqual(profiles.DEFAULT_MINECRAFT, '1.21.1')
        for key, facts in expected.items():
            selected = profiles.profile(*key)
            self.assertIs(selected, profiles.PROFILES[key])
            self.assertEqual((selected.java_version, selected.family, selected.game_namespace,
                              selected.version, selected.sdk_role), facts)
            self.assertEqual(selected.class_prefix, facts[2].replace('.', '/') + '/')
        self.assertIs(profiles.profile('1.20.1', 'forge').binding,
                      profiles.profile('1.20.1', 'neoforge').binding)

    def test_fabric_dependencies_pin_the_full_official_artifacts(self):
        pins = {
            '1.16.5': ('0.42.0+1.16', '3df8dd503f35aa0ac9fab8ad9f9a369fdfd0b1ab544af19a3d626d948fb4586c'),
            '1.18.2': ('0.77.0+1.18.2', '6f822fb5aa481b4a6c1cfb8612bbfecc62a58e69d2c792f61a0eafa580e75999'),
            '1.20.1': ('0.92.12+1.20.1', '4197ff4fbdac13cffccd267c1bc59e9fbabb2b5683a9d5f8023f4b5ea16a1c1e'),
            '1.21.1': ('0.116.7+1.21.1', '08018cc48c97415a38016a00dbd5a2c7a460ba6b7a051690a8cb3dbb5e8482a4'),
        }
        for minecraft, pin in pins.items():
            selected = profiles.profile(minecraft, 'fabric')
            self.assertEqual((selected.api_version, selected.api_sha256), pin)
            self.assertEqual(selected.api.mod_id, 'fabric' if minecraft == '1.16.5' else 'fabric-api')
            for loader in ('forge', 'neoforge'):
                if (minecraft, loader) not in profiles.PROFILES:
                    continue
                selected = profiles.profile(minecraft, loader)
                self.assertIsNone(selected.api)
                self.assertIsNone(selected.api_version)
                self.assertIsNone(selected.api_sha256)

    def test_current_mixin_sets_are_exact_and_remain_per_binding(self):
        server = ('ServerConnectionMixin', 'ServerLoginConnectionMixin', 'ServerLoginMixin')
        client = ('ClientConnectionMixin', 'IntegratedServerMixin', 'MultiplayerListMixin',
                  'MultiplayerListAccessor', 'MultiplayerScreenMixin')
        expected = {
            ('1.16.5', 'fabric'): (server, client + ('FabricLoginNetworkingMixin', 'LanNarrationMixin'),
                                  'p2wlan-universal-fabric-1.16.5-server-refmap.json',
                                  'p2wlan-universal-fabric-1.16.5-client-refmap.json'),
            ('1.16.5', 'forge'): (server, client + ('ClientShutdownMixin', 'LanNarrationMixin'),
                                 'p2wlan-universal-forge-1.16.5-server-refmap.json',
                                 'p2wlan-universal-forge-1.16.5-client-refmap.json'),
            ('1.20.1', 'fabric'): (server, client + ('FabricLoginNetworkingMixin',),
                                  'p2wlan-universal-fabric-1.20.1-server-refmap.json',
                                  'p2wlan-universal-fabric-1.20.1-client-refmap.json'),
            ('1.20.1', 'forge'): (server, client, 'p2wlan-universal-forge-1.20.1-server-refmap.json',
                                 'p2wlan-universal-forge-1.20.1-client-refmap.json'),
            ('1.21.1', 'fabric'): (server + ('ServerConfigurationMixin',), client,
                                  'p2wlan-universal-fabric-server-refmap.json',
                                  'p2wlan-universal-fabric-client-refmap.json'),
            ('1.21.1', 'forge'): (server + ('ServerConfigurationMixin',), client, None, None),
            ('1.18.2', 'fabric'): (server, client + ('FabricLoginNetworkingMixin',),
                                  'p2wlan-universal-fabric-1.18.2-server-refmap.json',
                                  'p2wlan-universal-fabric-1.18.2-client-refmap.json'),
            ('1.18.2', 'forge'): (server, client + ('ClientShutdownMixin',),
                                 'p2wlan-universal-forge-1.18.2-server-refmap.json',
                                 'p2wlan-universal-forge-1.18.2-client-refmap.json'),
        }
        for key, facts in expected.items():
            mixins = profiles.profile(*key).mixins
            self.assertEqual((mixins.server, mixins.client, mixins.server_refmap, mixins.client_refmap), facts)
            binding = profiles.profile(*key).binding
            suffix = {'1.16.5': '.1165', '1.18.2': '.1182', '1.20.1': '.1201', '1.21.1': ''}[key[0]]
            family = '.fabric' if key[1] == 'fabric' else ''
            self.assertEqual(binding.server_config, f'p2wlan.dispatch{family}{suffix}.json')
            self.assertEqual(binding.client_config, f'p2wlan.dispatch{family}{suffix}.client.json')
        for minecraft in profiles.MINECRAFT_VERSIONS:
            if (minecraft, 'neoforge') not in profiles.PROFILES:
                continue
            self.assertIs(profiles.profile(minecraft, 'forge').mixins,
                          profiles.profile(minecraft, 'neoforge').mixins)

    def test_profiles_and_all_nested_current_facts_are_immutable(self):
        selected = profiles.profile()
        with self.assertRaises(TypeError):
            profiles.PROFILES['1.18.2', 'fabric'] = selected
        for value, field, changed in [(selected, 'sdk_role', 'other'),
                                       (selected.binding, 'java_version', 8),
                                       (selected.loader_pin, 'version', 'unverified'),
                                       (selected.api, 'artifact_sha256', '0' * 64),
                                       (selected.api, 'mod_id', 'unverified'),
                                       (selected.mixins, 'server', ('UnknownMixin',))]:
            with self.subTest(field=field), self.assertRaises(FrozenInstanceError):
                setattr(value, field, changed)
        self.assertIsInstance(selected.mixins.server, tuple)
        self.assertIsInstance(selected.mixins.client, tuple)

    def test_unknown_versions_and_missing_pairs_never_inherit_another_profile(self):
        for minecraft in ('1.18.1', '1.16.4', '1.18', '1.16', '1.20', '1.20.2', '1.21', '1.21.2', None, [], {}, True):
            for loader in profiles.LOADERS:
                with self.subTest(minecraft=minecraft, loader=loader), self.assertRaisesRegex(profiles.ProfileError, 'MINECRAFT_PROFILE_VERSION_UNSUPPORTED'):
                    profiles.profile(minecraft, loader)
        for loader in ('', 'unknown', 'NeoForge', None, [], {}, True):
            with self.subTest(loader=loader), self.assertRaisesRegex(profiles.ProfileError, 'MINECRAFT_PROFILE_LOADER_UNSUPPORTED'):
                profiles.profile(loader=loader)
        with self.assertRaisesRegex(profiles.ProfileError, 'MINECRAFT_PROFILE_PAIR_UNSUPPORTED'):
            profiles.profile('1.16.5', 'neoforge')
        # Sparse future matrices use exact lookup, even when another installed
        # loader shares the same SDK and game family.
        sparse = {key: value for key, value in profiles.PROFILES.items() if key != ('1.20.1', 'neoforge')}
        with patch.object(profiles, 'PROFILES', MappingProxyType(sparse)):
            with self.assertRaisesRegex(profiles.ProfileError, 'MINECRAFT_PROFILE_PAIR_UNSUPPORTED'):
                profiles.profile('1.20.1', 'neoforge')
            self.assertEqual(profiles.profile('1.20.1', 'forge').version, '47.3.2')

    def test_type_can_describe_an_unpublished_distinct_binding_without_registering_it(self):
        candidate = replace(profiles.profile(), binding=replace(profiles.profile().binding,
                            minecraft='9.9.9', java_version=17,
                            game_namespace='io.p2wlan.mc.fabric.game.v9_9_9',
                            mixins=profiles.MixinSelection(('FutureServerMixin',), (),
                                                          'future-server-refmap.json', None)))
        self.assertEqual(candidate.java_version, 17)
        self.assertEqual(candidate.mixins.client, ())
        with self.assertRaisesRegex(profiles.ProfileError, 'MINECRAFT_PROFILE_VERSION_UNSUPPORTED'):
            profiles.profile(candidate.minecraft, candidate.loader)

    def test_required_sdk_inputs_use_exact_roles_and_one_shared_legacy_forge_input(self):
        inputs = profiles.sdk_inputs()
        self.assertEqual(tuple(inputs), ('fabric', 'neoforge', 'forge', 'legacy_forge', 'legacy_fabric', 'fabric_1182', 'forge_1182', 'fabric_1165', 'forge_1165'))
        self.assertEqual(len(inputs), 9)
        for selected in profiles.PROFILES.values():
            source = profiles.sdk_input(selected.minecraft, selected.loader)
            self.assertIs(source, inputs[selected.sdk_role])
            self.assertIs(source.binding, selected.binding)
            if selected.loader != 'neoforge' or selected.minecraft != '1.20.1':
                self.assertIs(source.loader_pin, selected.loader_pin)
                self.assertIs(source.api, selected.api)
            self.assertEqual(source.archive_name('0.1.0'), f'p2wlan-{source.loader}-{source.minecraft}-0.1.0.jar')
        self.assertIs(profiles.sdk_input('1.20.1', 'neoforge'), profiles.sdk_input('1.20.1', 'forge'))
        with self.assertRaises(TypeError):
            inputs['future'] = inputs['fabric']
        with self.assertRaises(FrozenInstanceError):
            inputs['fabric'].directory = 'other'
        with self.assertRaises(TypeError):
            profiles.SDK_INPUTS['future'] = inputs['fabric']
        sparse = {key: selected for key, selected in profiles.PROFILES.items() if key != ('1.21.1', 'neoforge')}
        with patch.object(profiles, 'PROFILES', MappingProxyType(sparse)):
            self.assertEqual(set(profiles.sdk_inputs()), set(inputs) - {'neoforge'})
        missing = dict(profiles.PROFILES)
        missing['1.21.1', 'fabric'] = replace(missing['1.21.1', 'fabric'], sdk_role='unknown')
        with patch.object(profiles, 'PROFILES', MappingProxyType(missing)), self.assertRaisesRegex(profiles.ProfileError, 'SDK_INPUT_MISSING'):
            profiles.sdk_inputs()

    def test_1182_sdk_bindings_admit_only_the_two_exact_installations(self):
        fabric = profiles.sdk_input('1.18.2', 'fabric')
        forge = profiles.sdk_input('1.18.2', 'forge')
        self.assertEqual((fabric.role, fabric.loader_pin.version, fabric.api.version),
                         ('fabric_1182', '0.16.14', '0.77.0+1.18.2'))
        self.assertEqual(fabric.api.artifact_sha256, '6f822fb5aa481b4a6c1cfb8612bbfecc62a58e69d2c792f61a0eafa580e75999')
        self.assertEqual((forge.role, forge.loader_pin.version, forge.fixture_loader_range, forge.fixture_pack_format),
                         ('forge_1182', '40.3.12', '[40,41)', 8))
        for source, namespace in ((fabric, 'io.p2wlan.mc.fabric.game.v1_18_2'),
                                  (forge, 'io.p2wlan.mc.game.v1_18_2')):
            self.assertEqual((source.minecraft, source.binding.java_version, source.binding.game_namespace),
                             ('1.18.2', 17, namespace))
            self.assertIn(source.role, profiles.sdk_inputs())
            self.assertIs(profiles.profile(source.minecraft, source.loader).binding, source.binding)
        with self.assertRaisesRegex(profiles.ProfileError, 'PAIR_UNSUPPORTED'):
            profiles.profile('1.18.2', 'neoforge')
        for minecraft, loader in [('1.18.2', 'neoforge'), ('1.18.1', 'fabric'), ('1.16.4', 'forge'),
                                   ('1.21.1', 'unknown'), ([], 'fabric'), ('1.21.1', {})]:
            with self.subTest(minecraft=minecraft, loader=loader), self.assertRaisesRegex(profiles.ProfileError, 'SDK_PAIR_UNSUPPORTED'):
                profiles.sdk_input(minecraft, loader)

    def test_1165_sdk_bindings_admit_only_the_two_exact_installations(self):
        for loader, role, directory, java, pin in (
                ('fabric', 'fabric_1165', 'java', 17, '0.16.14'),
                ('forge', 'forge_1165', 'java/forge-1165', 17, '36.2.42')):
            source = profiles.sdk_input('1.16.5', loader)
            self.assertEqual((source.role, source.directory, source.binding.java_version, source.loader_pin.version),
                             (role, directory, java, pin))
            self.assertIn(role, profiles.sdk_inputs())
            self.assertIn(('1.16.5', loader), profiles.PROFILES)
            self.assertIs(source.binding, profiles.profile('1.16.5', loader).binding)
            self.assertIs(source.loader_pin, profiles.profile('1.16.5', loader).loader_pin)
        fabric = profiles.sdk_input('1.16.5', 'fabric')
        self.assertEqual((fabric.api.version, fabric.api.artifact_sha256),
                         ('0.42.0+1.16', '3df8dd503f35aa0ac9fab8ad9f9a369fdfd0b1ab544af19a3d626d948fb4586c'))
        self.assertEqual(fabric.api.mod_id, 'fabric')
        forge = profiles.sdk_input('1.16.5', 'forge')
        self.assertEqual((forge.fixture_loader_range, forge.fixture_pack_format), ('[36,37)', 6))
        with self.assertRaisesRegex(profiles.ProfileError, 'SDK_PAIR_UNSUPPORTED'):
            profiles.sdk_input('1.16.5', 'neoforge')

    def test_forge_metadata_ranges_are_ordered_exact_pins_independent_of_registration_order(self):
        expected = (('1.16.5', 'forge'), ('1.18.2', 'forge'), ('1.20.1', 'neoforge'), ('1.20.1', 'forge'), ('1.21.1', 'forge'))
        self.assertEqual(tuple((item.minecraft, item.loader) for item in profiles.forge_profiles()), expected)
        self.assertEqual(profiles.forge_range(), '[36.2.42],[40.3.12],[47.1.106],[47.3.2],[52.1.16]')
        self.assertEqual(profiles.fml_range(), '[36,37),[40,41),[47,48),[52,53)')
        self.assertEqual(profiles.game_range(), '[1.16.5],[1.18.2],[1.20.1],[1.21.1]')
        reversed_profiles = MappingProxyType(dict(reversed(list(profiles.PROFILES.items()))))
        with patch.object(profiles, 'PROFILES', reversed_profiles):
            self.assertEqual(tuple((item.minecraft, item.loader) for item in profiles.forge_profiles()), expected)
            self.assertEqual(profiles.forge_range(), '[36.2.42],[40.3.12],[47.1.106],[47.3.2],[52.1.16]')
            self.assertEqual(profiles.fml_range(), '[36,37),[40,41),[47,48),[52,53)')
            self.assertEqual(profiles.game_range(), '[1.16.5],[1.18.2],[1.20.1],[1.21.1]')
        baseline = MappingProxyType({key: item for key, item in profiles.PROFILES.items() if key[0] not in {'1.16.5', '1.18.2'}})
        with patch.object(profiles, 'PROFILES', baseline):
            self.assertEqual(profiles.forge_range(), '[47.1.106],[47.3.2],[52.1.16]')
            self.assertEqual(profiles.fml_range(), '[47,48),[52,53)')
            self.assertEqual(profiles.game_range(), '[1.20.1],[1.21.1]')

    def test_metadata_ranges_keep_numeric_version_order_and_reject_unknown_qualifiers(self):
        values = dict(profiles.PROFILES)
        selected = values['1.18.2', 'forge']
        values['1.18.2', 'forge'] = replace(selected, loader_pin=replace(selected.loader_pin, version='9.3.12'))
        with patch.object(profiles, 'PROFILES', MappingProxyType(values)):
            self.assertEqual(profiles.forge_range(), '[9.3.12],[36.2.42],[47.1.106],[47.3.2],[52.1.16]')
        values['1.18.2', 'forge'] = replace(selected, loader_pin=replace(selected.loader_pin, version='40.3.12-unverified'))
        with patch.object(profiles, 'PROFILES', MappingProxyType(values)), self.assertRaisesRegex(profiles.ProfileError, 'RANGE_VERSION_UNSUPPORTED'):
            profiles.forge_range()

    def test_registry_source_bytes_are_covered_by_the_native_input_digest(self):
        from native_bundle import source_digest
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for path in ('Cargo.toml', 'minecraft-mod/native/Cargo.toml', 'minecraft-mod/native/Cargo.lock',
                         'minecraft-mod/tools/minecraft_profiles.py'):
                target = root / path; target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(b'isolated source-binding input')
            before = source_digest(root)
            (root / 'minecraft-mod/tools/minecraft_profiles.py').write_bytes(b'changed registry facts')
            self.assertNotEqual(source_digest(root), before)


if __name__ == '__main__':
    unittest.main()
