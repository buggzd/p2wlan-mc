"""The Forge16 projection changes pinned SDK types, retaining shared effects."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import forge_1165_sources as forge
from source_contract import ContractError


class Forge1165SourcesTests(unittest.TestCase):
    def test_aliases_change_declared_types_and_descriptors_but_not_comments_literals_or_members(self):
        source = '''package io.p2wlan.mc.game;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.vertex.PoseStack;
class Probe {
// Connection and net.minecraft.network.Connection remain documentation.
String text="Connection net.minecraft.network.Connection";
Connection original; Component message; PoseStack draw;
Object test() { return original.getPacketListener(); }
@At(target="Lnet/minecraft/server/players/PlayerList;placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;)V")
void place() {}
}'''
        result, counts = forge.rewrite(source)
        self.assertIn('import net.minecraft.network.NetworkManager;', result)
        self.assertIn('NetworkManager original; ITextComponent message; MatrixStack draw;', result)
        self.assertIn('original.getPacketListener()', result)
        self.assertIn('// Connection and net.minecraft.network.Connection remain documentation.', result)
        self.assertIn('String text="Connection net.minecraft.network.Connection";', result)
        self.assertIn('Lnet/minecraft/server/management/PlayerList;placeNewPlayer(Lnet/minecraft/network/NetworkManager;Lnet/minecraft/entity/player/ServerPlayerEntity;)V', result)
        self.assertEqual(counts['net.minecraft.network.Connection'], 3)
        self.assertEqual(counts['com.mojang.blaze3d.vertex.PoseStack'], 2)

    def test_imported_nested_and_reviewed_inherited_callback_types_are_exact(self):
        source = '''package io.p2wlan.mc.game;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.RegistryAccess;
class Probe extends Button {
Button.OnPress action; OnPress inherited; OnTooltip hint;
RegistryAccess.RegistryHolder registries;
}'''
        result, _ = forge.rewrite(source)
        self.assertIn('Button.IPressable action; IPressable inherited; ITooltip hint;', result)
        self.assertIn('DynamicRegistries.Impl registries;', result)
        for source in ('import net.minecraft.unknown.UnknownType;',
                       'class Probe { net.minecraft.unknown.UnknownType type; }',
                       '@At(target="Lnet/minecraft/unknown/UnknownType;call()V") class Probe {}',
                       'import com.mojang.blaze3d.unknown.UnknownType;'):
            with self.subTest(source=source), self.assertRaises(ContractError): forge.rewrite(source)

    def test_source_owner_connection_scope_and_both_login_commits_remain_shared(self):
        sources = forge.inputs()
        key = 'shared/server/java/io/p2wlan/mc/game/server/mixin/ServerLoginMixin.java'
        result, _ = forge.rewrite(sources[key][0])
        self.assertIn('@Shadow @Final private NetworkManager connection;', result)
        self.assertEqual(result.count('require = 2, allow = 2'), 2)
        self.assertIn('LoopbackAdmission.isProtected(channel) && !LoopbackAdmission.isAdmitted(channel)', result)
        self.assertIn('p2wlan$playerCommit(CallbackInfo result) { p2wlan$currentWorld(result); }', result)
        self.assertIn('server.isSingleplayer() && !server.isDedicatedServer()', result)
        key = 'api/client/java/io/p2wlan/mc/game/client/connect/MinecraftConnect.java'
        result, _ = forge.rewrite(sources[key][0])
        self.assertIn('ScopedClientConnect.wrap(connection, endpoint.address(), vanilla)', result)
        self.assertIn('.addLast("packet_handler", connection)', result)
        self.assertNotIn('new NetworkManager(', result)
        self.assertNotIn('syncUninterruptibly', result)

    def test_all_45_inputs_are_bound_to_unique_source_and_rejected_before_partial_publish(self):
        source = forge.inputs()
        self.assertEqual(len(source), 45)
        self.assertEqual(set(source), set(forge.INPUT_SHA256))
        with tempfile.TemporaryDirectory(dir=forge.PROJECT / '.evidence') as temporary:
            root = Path(temporary); output = root / 'build/generated'
            forge.prepare(output)
            records = json.loads((output / 'source-bindings.json').read_text())
            self.assertEqual(set(records), set(source))
            self.assertEqual({p.relative_to(output).as_posix() for p in output.rglob('*.java')}, set(source))
            for key, (text, original) in source.items():
                value = records[key]
                self.assertEqual(set(value), {'original_sha256', 'game1165_sha256', 'mcp_sha256', 'class_aliases'})
                self.assertEqual(value['original_sha256'], original)
                self.assertEqual(value['game1165_sha256'], hashlib.sha256(text.encode()).hexdigest())
                self.assertEqual(value['mcp_sha256'], hashlib.sha256((output / key).read_bytes()).hexdigest())
            first = next(iter(source)); altered = source | {first: (source[first][0] + '\n// changed contract\n', source[first][1])}
            with patch.object(forge, 'inputs', return_value=altered), self.assertRaisesRegex(ContractError, 'input_changed'):
                forge.prepare(root / 'build/rejected')
            self.assertFalse((root / 'build/rejected').exists())
            extra = source | {'api/client/java/unknown.java': ('class Unknown {}', '0' * 64)}
            with patch.object(forge, 'inputs', return_value=extra), self.assertRaisesRegex(ContractError, 'input_inventory'):
                forge.prepare(root / 'build/extra')
            self.assertFalse((root / 'build/extra').exists())

    def test_api_inventory_and_generated_output_links_are_rejected(self):
        with tempfile.TemporaryDirectory(dir=forge.PROJECT / '.evidence') as temporary:
            root = Path(temporary)
            api = root / 'api'; api.mkdir()
            for relative in forge.API_INVENTORY:
                target = api / relative; target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((forge.API / relative).read_bytes())
            (api / 'Extra.java').write_text('package io.p2wlan.mc.game; class Extra {}')
            with patch.object(forge, 'API', api), self.assertRaisesRegex(ContractError, 'api_inventory'): forge.inputs()
            output = root / 'build/generated'; forge.prepare(output)
            link = root / 'build/link'; link.symlink_to(output, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, 'OUTPUT_LINK'): forge.prepare(link)
            with self.assertRaisesRegex(ValueError, 'NOT_GENERATED'): forge.prepare(root / 'tracked')


if __name__ == '__main__': unittest.main()
