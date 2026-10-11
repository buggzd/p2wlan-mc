"""Loader/version source ownership, distinct from real-game verification."""
from pathlib import Path
import unittest

PROJECT = Path(__file__).resolve().parents[1]

class SharedGameTests(unittest.TestCase):
    def test_both_loaders_compile_one_physical_game_frontend(self):
        shared = PROJECT / 'java/game-1.21.1/src/client/java'
        files = list(shared.rglob('*.java'))
        self.assertGreaterEqual(len(files), 28)
        for path in files:
            source = path.read_text()
            for prefix in ('net.fabricmc.', 'net.neoforged.', 'net.minecraftforge.', 'io.p2wlan.mc.fabric.', 'io.p2wlan.mc.neoforge.'):
                self.assertNotIn(prefix, source, str(path))
        for adapter in ('fabric', 'neoforge'):
            build = (PROJECT / f'java/adapters/{adapter}-1.21.1/build.gradle').read_text()
            self.assertIn("rootProject.file('game-1.21.1/src/client/java')", build)
            self.assertFalse(list((PROJECT / f'java/adapters/{adapter}-1.21.1/src').rglob('AccountScreen.java')))
            self.assertFalse(list((PROJECT / f'java/adapters/{adapter}-1.21.1/src').rglob('GameJoins.java')))
        common = PROJECT / 'java/common/src/main/java'
        for path in common.rglob('*.java'):
            self.assertNotIn('import net.minecraft.', path.read_text())

    def test_platform_binding_delegates_world_and_native_owners(self):
        code = (PROJECT / 'java/game-1.21.1/src/client/java/io/p2wlan/mc/game/client/ClientPlatform.java').read_text()
        self.assertIn('CURRENT.compareAndSet(null, new Binding', code)
        self.assertIn('binding().worlds().apply(server)', code)
        self.assertNotIn('new AgentLifecycle', code)
        self.assertNotIn('new WorldSessions', code)

if __name__ == '__main__': unittest.main()
