"""The ARM64 fixture substitutes one complete, exact Java/native family."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from fixture_lwjgl import libraries, identity, replacement


class FixtureLwjglTests(unittest.TestCase):
    def original(self):
        return {'org.lwjgl:' + module + ':3.2.1': {'path': module} for module in
                ('lwjgl', 'lwjgl-glfw', 'lwjgl-jemalloc', 'lwjgl-openal', 'lwjgl-opengl', 'lwjgl-stb', 'lwjgl-tinyfd')}

    def complete_family(self, minecraft):
        original = self.original() | {'other:library:1': {'path': 'other'}}
        result = libraries(minecraft, ('osx', 'arm64'), original)
        self.assertEqual(result['other:library:1'], original['other:library:1'])
        self.assertEqual(len(result), 15)
        self.assertEqual(len(original), 8)
        for name in original:
            if name.startswith('org.lwjgl:'):
                self.assertNotIn(name, result)
                module = name.split(':')[1]
                self.assertIn('org.lwjgl:' + module + ':3.3.2', result)
                self.assertIn('org.lwjgl:' + module + ':3.3.2:natives-macos-arm64', result)
        for name, value in replacement(minecraft, ('osx', 'arm64')).items():
            self.assertEqual(value['url'], 'https://repo.maven.apache.org/maven2/' + value['path'])
            self.assertGreater(value['size'], 0)
            self.assertRegex(value['sha256'], r'^[a-f0-9]{64}$')
        self.assertEqual(len(identity(minecraft, ('osx', 'arm64'))['artifacts']), 14)

    def test_1182_all_seven_bindings_and_matching_native_versions_are_substituted_together(self):
        self.complete_family('1.18.2')

    def test_1165_all_seven_bindings_and_matching_native_versions_are_substituted_together(self):
        self.complete_family('1.16.5')

    def test_other_platforms_and_baselines_preserve_original_selection(self):
        original = self.original()
        for minecraft, host in (('1.20.1', ('osx', 'arm64')), ('1.18.2', ('osx', 'x86_64')),
                                ('1.18.2', ('linux', 'x86_64')), ('1.18.2', ('windows', 'x86_64'))):
            self.assertEqual(libraries(minecraft, host, original), original)
            self.assertIsNone(identity(minecraft, host))

    def test_incomplete_or_changed_metadata_fails_before_selecting_substitutes(self):
        missing = self.original(); missing.pop('org.lwjgl:lwjgl:3.2.1')
        changed = self.original(); changed['org.lwjgl:lwjgl-glfw:3.3.0'] = changed.pop('org.lwjgl:lwjgl-glfw:3.2.1')
        for original in (missing, changed, {}):
            with self.assertRaisesRegex(ValueError, 'GAME_FIXTURE_LWJGL_SOURCE_CONTRACT'):
                libraries('1.18.2', ('osx', 'arm64'), original)


if __name__ == '__main__':
    unittest.main()
