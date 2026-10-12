"""Universal startup schema/source boundaries; executable policy lives in Java."""
import json
from pathlib import Path
import re
import unittest

MODULE = Path(__file__).resolve().parents[1] / 'java/universal-bootstrap'


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
        }
        resources = MODULE / 'src/main/resources'
        self.assertEqual({path.name for path in resources.glob('*.json')}, set(expected))
        for name, (package, plugin) in expected.items():
            with self.subTest(config=name):
                self.assertEqual(json.loads((resources / name).read_text()), {
                    'required': True, 'minVersion': '0.8.5', 'package': package,
                    'plugin': 'io.p2wlan.mc.bootstrap.' + plugin,
                    'compatibilityLevel': 'JAVA_17', 'mixins': [], 'injectors': {'defaultRequire': 1},
                })
                for ordinary in ('io.p2wlan.mc.common', 'io.p2wlan.mc.game.client', 'io.p2wlan.mc.fabric.game.client'):
                    self.assertFalse(ordinary.startswith(package + '.'))

    def test_bootstrap_imports_no_game_loader_or_connection_owner(self):
        files = list((MODULE / 'src/main/java').rglob('*.java'))
        self.assertEqual(len(files), 4)
        for path in files:
            text = path.read_text()
            with self.subTest(file=path.name):
                self.assertIsNone(re.search(r'^import\s+(?:static\s+)?(?:net\.(?:minecraft|fabricmc|minecraftforge|neoforged)|cpw)\.', text, re.MULTILINE))
                for forbidden in ('AgentLifecycle', 'WorldSessions', 'new Thread(', 'Executors.', 'java.net.Socket', 'java.net.http.'):
                    self.assertNotIn(forbidden, text)

    def test_standalone_java_17_sdk_and_explicit_assertion_harness(self):
        build = (MODULE / 'build.gradle').read_text()
        for contract in ("id 'java-library'", "archivesName = 'p2wlan-universal-bootstrap'", 'options.release = 17',
                         'net.fabricmc:sponge-mixin:0.15.5+mixin.0.8.7', 'org.ow2.asm:asm-tree:9.7.1',
                         "tasks.register('bootstrapPolicyTest', JavaExec)", 'check.dependsOn bootstrapPolicyTest'):
            self.assertIn(contract, build)
        self.assertNotIn('from(sourceSets.test.output)', build)
        self.assertNotIn('minecraft ', build)


if __name__ == '__main__':
    unittest.main()
