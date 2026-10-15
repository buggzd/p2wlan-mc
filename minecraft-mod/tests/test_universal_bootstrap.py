"""Universal startup schema/source boundaries; executable policy lives in Java."""
import json
from pathlib import Path
import re
import sys
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / 'java/universal-bootstrap'
sys.path.insert(0, str(MODULE.parents[1] / 'tools'))
from universal_profile_sources import dispatch_configs, generate, java_source


class UniversalBootstrapTests(unittest.TestCase):
    def test_dispatch_packages_are_exact_narrow_mixin_namespaces(self):
        expected = {
            'p2wlan.dispatch.json': ('io.p2wlan.mc.game.server.mixin', 'ServerMixinPlugin'),
            'p2wlan.dispatch.client.json': ('io.p2wlan.mc.game.mixin', 'ClientMixinPlugin'),
            'p2wlan.dispatch.fabric.json': ('io.p2wlan.mc.fabric.game.server.mixin', 'ServerMixinPlugin'),
            'p2wlan.dispatch.fabric.client.json': ('io.p2wlan.mc.fabric.game.mixin', 'ClientMixinPlugin'),
            'p2wlan.dispatch.1201.json': ('io.p2wlan.mc.game.v1_20_1.server.mixin', 'ServerMixinPlugin'),
            'p2wlan.dispatch.1201.client.json': ('io.p2wlan.mc.game.v1_20_1.mixin', 'ClientMixinPlugin'),
            'p2wlan.dispatch.fabric.1201.json': ('io.p2wlan.mc.fabric.game.v1_20_1.server.mixin', 'ServerMixinPlugin'),
            'p2wlan.dispatch.fabric.1201.client.json': ('io.p2wlan.mc.fabric.game.v1_20_1.mixin', 'ClientMixinPlugin'),
            'p2wlan.dispatch.1182.json': ('io.p2wlan.mc.game.v1_18_2.server.mixin', 'ServerMixinPlugin'),
            'p2wlan.dispatch.1182.client.json': ('io.p2wlan.mc.game.v1_18_2.mixin', 'ClientMixinPlugin'),
            'p2wlan.dispatch.fabric.1182.json': ('io.p2wlan.mc.fabric.game.v1_18_2.server.mixin', 'ServerMixinPlugin'),
            'p2wlan.dispatch.fabric.1182.client.json': ('io.p2wlan.mc.fabric.game.v1_18_2.mixin', 'ClientMixinPlugin'),
            'p2wlan.dispatch.fabric.1165.json': ('io.p2wlan.mc.fabric.game.v1_16_5.server.mixin', 'ServerMixinPlugin'),
            'p2wlan.dispatch.fabric.1165.client.json': ('io.p2wlan.mc.fabric.game.v1_16_5.mixin', 'ClientMixinPlugin'),
            'p2wlan.dispatch.1165.json': ('io.p2wlan.mc.game.v1_16_5.server.mixin', 'ServerMixinPlugin'),
            'p2wlan.dispatch.1165.client.json': ('io.p2wlan.mc.game.v1_16_5.mixin', 'ClientMixinPlugin'),
        }
        configs = dispatch_configs()
        self.assertEqual(set(configs), set(expected))
        for name, (package, plugin) in expected.items():
            with self.subTest(config=name):
                self.assertEqual(configs[name], {
                    'required': True, 'minVersion': '0.8.4', 'package': package,
                    'plugin': 'io.p2wlan.mc.bootstrap.' + plugin,
                    'compatibilityLevel': 'JAVA_17', 'mixins': [], 'injectors': {'defaultRequire': 1},
                })
                for ordinary in ('io.p2wlan.mc.common', 'io.p2wlan.mc.game.client', 'io.p2wlan.mc.fabric.game.client'):
                    self.assertFalse(ordinary.startswith(package + '.'))

    def test_bootstrap_imports_no_game_loader_or_connection_owner(self):
        files = list((MODULE / 'src/main/java').rglob('*.java'))
        self.assertEqual(len(files), 4)
        sources = [(path.name, path.read_text()) for path in files] + [('UniversalProfiles.java', java_source())]
        for name, text in sources:
            with self.subTest(file=name):
                self.assertIsNone(re.search(r'^import\s+(?:static\s+)?(?:net\.(?:minecraft|fabricmc|minecraftforge|neoforged)|cpw)\.', text, re.MULTILINE))
                for forbidden in ('AgentLifecycle', 'WorldSessions', 'new Thread(', 'Executors.', 'java.net.Socket', 'java.net.http.'):
                    self.assertNotIn(forbidden, text)

    def test_registry_generation_is_deterministic_and_removes_stale_configs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            generate(root)
            before = {path.relative_to(root): path.read_bytes() for path in root.rglob('*') if path.is_file()}
            (root / 'resources/p2wlan.dispatch.stale.json').write_text('{}')
            generate(root)
            self.assertEqual({path.relative_to(root): path.read_bytes() for path in root.rglob('*') if path.is_file()}, before)
            self.assertEqual((root / 'java/io/p2wlan/mc/bootstrap/UniversalProfiles.java').read_text(), java_source())
        # Exact allowlist keys prevent a loader/version Cartesian product.
        self.assertIn('INSTALLATIONS.contains(loader.name() + "|" + version)', java_source())

    def test_older_profile_dispatch_is_exact_pair_and_preserves_original_login_guards(self):
        source = java_source()
        installations = source.split('private static final Set<String> INSTALLATIONS = Set.of(', 1)[1].split(');', 1)[0]
        self.assertIn('"FABRIC|1.18.2"', installations)
        self.assertIn('"FORGE|1.18.2"', installations)
        self.assertNotIn('"NEOFORGE|1.18.2"', installations)
        expected_server = 'List.of("ServerConnectionMixin", "ServerLoginConnectionMixin", "ServerLoginMixin")'
        for family, extra in (('FABRIC', 'FabricLoginNetworkingMixin'), ('FML', 'ClientShutdownMixin')):
            expected_client = 'List.of("ClientConnectionMixin", "IntegratedServerMixin", "MultiplayerListMixin", "MultiplayerListAccessor", "MultiplayerScreenMixin", "' + extra + '")'
            self.assertIn('case "' + family + '|1.18.2" -> client ? ' + expected_client + ' : ' + expected_server + ';', source)
        for loader in ('fabric', 'forge'):
            for side in ('server', 'client'):
                self.assertIn('"p2wlan-universal-' + loader + '-1.18.2-' + side + '-refmap.json"', source)

    def test_standalone_java_17_sdk_and_explicit_assertion_harness(self):
        build = (MODULE / 'build.gradle').read_text()
        for contract in ("id 'java-library'", "archivesName = 'p2wlan-universal-bootstrap'", 'options.release = 17',
                         'net.fabricmc:sponge-mixin:0.15.5+mixin.0.8.7', 'org.ow2.asm:asm-tree:9.7.1',
                         "tasks.register('bootstrapPolicyTest', JavaExec)", 'check.dependsOn bootstrapPolicyTest'):
            self.assertIn(contract, build)
        self.assertNotIn('from(sourceSets.test.output)', build)
        self.assertNotIn('minecraft ', build)

    def test_1165_has_exact_fabric_and_fml_seven_client_mixin_sets(self):
        source = java_source()
        installations = source.split('private static final Set<String> INSTALLATIONS = Set.of(', 1)[1].split(');', 1)[0]
        self.assertIn('"FABRIC|1.16.5"', installations)
        self.assertIn('"FORGE|1.16.5"', installations)
        self.assertNotIn('"NEOFORGE|1.16.5"', installations)
        server = 'List.of("ServerConnectionMixin", "ServerLoginConnectionMixin", "ServerLoginMixin", "ConsoleSourceMixin")'
        for family, loader, extra in (('FABRIC', 'fabric', 'FabricLoginNetworkingMixin'),
                                     ('FML', 'forge', 'ClientShutdownMixin')):
            names = 'List.of("ClientConnectionMixin", "IntegratedServerMixin", "MultiplayerListMixin", "MultiplayerListAccessor", "MultiplayerScreenMixin", "' + extra + '", "LanNarrationMixin")'
            self.assertIn('case "' + family + '|1.16.5" -> client ? ' + names + ' : ' + server + ';', source)
            for side in ('server', 'client'):
                self.assertIn('"p2wlan-universal-' + loader + '-1.16.5-' + side + '-refmap.json"', source)


if __name__ == '__main__':
    unittest.main()
