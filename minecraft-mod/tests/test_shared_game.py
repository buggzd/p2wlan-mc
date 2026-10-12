"""Loader/version source ownership, distinct from real-game verification."""
from pathlib import Path
import unittest

PROJECT = Path(__file__).resolve().parents[1]

class SharedGameTests(unittest.TestCase):
    def test_all_loaders_compile_one_physical_game_frontend(self):
        shared = PROJECT / 'java/game-1.21.1/src/client/java'
        files = list(shared.rglob('*.java'))
        self.assertGreaterEqual(len(files), 28)
        for path in files:
            source = path.read_text()
            for prefix in ('net.fabricmc.', 'net.neoforged.', 'net.minecraftforge.', 'io.p2wlan.mc.fabric.', 'io.p2wlan.mc.neoforge.'):
                self.assertNotIn(prefix, source, str(path))
        for adapter in ('fabric', 'neoforge', 'forge'):
            build = (PROJECT / f'java/adapters/{adapter}-1.21.1/build.gradle').read_text()
            if adapter == 'forge':
                self.assertIn("rootProject.file('../game-1.21.1')", build)
                self.assertIn("new File(game, 'src/client/java')", build)
            else:
                self.assertIn("rootProject.file('game-1.21.1/src/client/java')", build)
            self.assertFalse(list((PROJECT / f'java/adapters/{adapter}-1.21.1/src').rglob('AccountScreen.java')))
            self.assertFalse(list((PROJECT / f'java/adapters/{adapter}-1.21.1/src').rglob('GameJoins.java')))
        common = PROJECT / 'java/common/src/main/java'
        for path in common.rglob('*.java'):
            self.assertNotIn('import net.minecraft.', path.read_text())

    def test_operator_commands_have_one_implementation_and_original_owners(self):
        files = list((PROJECT / "java").rglob("WorldCommands.java"))
        files = [path for path in files if "build" not in path.parts]
        self.assertEqual(len(files), 1)
        source = files[0].read_text()
        for name in ("new WorldSessions", "new AgentLifecycle", "NeoForgeP2wlan", "FabricP2wlan", "net.minecraft.client."):
            self.assertNotIn(name, source)
        self.assertIn("Function<MinecraftServer, WorldSessions.World<MinecraftServer>>", source)
        self.assertIn("agent.isCurrent(client)", source)
        self.assertIn("world::isCurrent", source)
        for adapter in ("fabric", "neoforge"):
            build = (PROJECT / f"java/adapters/{adapter}-1.21.1/build.gradle").read_text()
            self.assertIn("rootProject.file('game-1.21.1/src/server/java')", build)

    def test_server_mixin_and_local_resource_policy_are_shared(self):
        files = [path for path in (PROJECT / 'java').rglob('ServerConnectionMixin.java') if 'build' not in path.parts]
        self.assertEqual(len(files), 1)
        self.assertFalse(list((PROJECT / 'java').rglob('ServerNetworkIoMixin.java')))
        self.assertIn('game-1.21.1', files[0].parts)
        common = (PROJECT / 'java/common/src/main/java/io/p2wlan/mc/common/LayerNativeResources.java').read_text()
        self.assertIn('depth < 8', common)
        for prefix in ('cpw.mods.', 'net.minecraftforge.', 'net.neoforged.', 'net.minecraft.'):
            self.assertNotIn(prefix, common)
        for adapter in ('neoforge', 'forge'):
            glue = (PROJECT / f'java/adapters/{adapter}-1.21.1/src/main/java/io/p2wlan/mc/{adapter}/LocalNativeResources.java').read_text()
            self.assertIn('new LayerNativeResources', glue)
            for name in ('reference.open()', 'getResources(name)', 'Files.newInputStream', 'RESOURCE_NOT_LOCAL'):
                self.assertNotIn(name, glue)

    def test_platform_binding_delegates_world_and_native_owners(self):
        code = (PROJECT / 'java/game-1.21.1/src/client/java/io/p2wlan/mc/game/client/ClientPlatform.java').read_text()
        self.assertIn('CURRENT.compareAndSet(null, new Binding', code)
        self.assertIn('binding().worlds().apply(server)', code)
        self.assertNotIn('new AgentLifecycle', code)
        self.assertNotIn('new WorldSessions', code)

if __name__ == '__main__': unittest.main()
