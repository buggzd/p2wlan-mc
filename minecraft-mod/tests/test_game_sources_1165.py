"""Reject unreviewed 1.16.5 API changes while retaining shared security logic."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import game_sources_1165 as game
from source_contract import ContractError


class Game1165SourcesTests(unittest.TestCase):
    def source(self, name, tree='client', package='client/screen/'):
        relative = Path(tree + '/java/io/p2wlan/mc/game/' + package + name + '.java')
        return relative, (game.SOURCE / relative).read_text()

    def test_login_checks_original_owner_at_both_direct_player_effects(self):
        relative, source = self.source('ServerLoginMixin', 'server', 'server/mixin/')
        result, _ = game.adapt(source, relative)
        self.assertIn('@Inject(method = {"handleHello", "handleAcceptedLogin"}', result)
        self.assertIn('@Inject(method = {"tick", "handleAcceptedLogin"}', result)
        self.assertIn(game.PLAYER_COMMIT, result)
        self.assertEqual(result.count('require = 2, allow = 2'), 2)
        self.assertIn('p2wlan$playerCommit(CallbackInfo result) { p2wlan$currentWorld(result); }', result)
        self.assertIn('LoopbackAdmission.isProtected(channel) && !LoopbackAdmission.isAdmitted(channel)', result)
        self.assertIn('server.isSingleplayer() && !server.isDedicatedServer()', result)
        self.assertIn('@Shadow @Final private Connection connection;', result)
        self.assertNotIn('"placeNewPlayer"}', result)
        with self.assertRaises(ContractError): game.adapt(source.replace('"handleLoginAcknowledgement"', '"unexpectedCommit"'), relative)

    def test_password_uses_old_label_only_narration_without_value_retention(self):
        relative, source = self.source('PasswordField')
        result, _ = game.adapt(source, relative)
        self.assertIn('protected net.minecraft.network.chat.MutableComponent createNarrationMessage()', result)
        self.assertIn('return getMessage().copy();', result)
        self.assertIn('"*".repeat(value.length())', result)
        self.assertIn('Screen.isCopy(key) || Screen.isCut(key)', result)
        self.assertNotIn('NarrationElement', result)
        self.assertNotIn('getValue()', result)
        with self.assertRaises(ContractError): game.adapt(source.replace('getMessage()', 'getValue()'), relative)

    def test_old_widget_effects_keep_inputs_and_drawing_in_the_same_screen(self):
        for name, count in game.WIDGET_COUNTS.items():
            relative, source = self.source(name); result, _ = game.adapt(source, relative)
            self.assertEqual(result.count('addButton('), count)
            self.assertNotIn('addRenderableWidget', result)
            with self.assertRaisesRegex(ContractError, 'widget_inventory'):
                game.adapt(source.replace('addRenderableWidget(', 'unreviewedWidget(', 1), relative)
        relative, source = self.source('VirtualLanEntry', package='client/lan/')
        result, _ = game.adapt(source, relative)
        self.assertIn('public Component getNarration()', result)
        self.assertNotIn('@Override public Component getNarration()', result)
        mixin = (game.PROJECT / 'java/game-api-1.16.5/src/client/java/io/p2wlan/mc/game/mixin/LanNarrationMixin.java').read_text()
        self.assertIn('entry instanceof VirtualLanEntry row', mixin)
        self.assertIn('row.getNarration().getString()', mixin)

    def test_gson_floor_keeps_exact_fixture_fields_and_human_label_checks(self):
        for name, tree in (('GameFixtureData', 'fixtureCommon'), ('GameClientChecks', 'localGameClientFixture')):
            relative, source = self.source(name, tree, 'fixture/')
            result, _ = game.adapt(source, relative)
            self.assertIn('new JsonParser().parse(', result)
            self.assertIn('io.p2wlan.mc.common.JsonTrees.fields(', result)
            self.assertNotIn('.keySet()', result)
            self.assertNotIn('JsonParser.parseString', result)
            if name == 'GameClientChecks':
                self.assertIn('entry.getNarration().getString()', result)
                self.assertIn('!narration.contains(peer) && !narration.isBlank()', result)
                self.assertEqual(result.count('client.getWindow().getWidth(),client.getWindow().getHeight(),client.getMainRenderTarget()'), len(game.SCREENSHOTS))
            with self.assertRaises(ContractError): game.adapt(source.replace('.keySet().equals(', '.unreviewedFields().equals('), relative)

    def test_unique_shared_source_inventory_is_digest_bound_before_publish(self):
        with tempfile.TemporaryDirectory(dir=game.PROJECT / '.evidence') as temporary:
            root = Path(temporary); source = root / 'source'; source.mkdir()
            for relative in game.INVENTORY:
                path = source / relative; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes((game.SOURCE / relative).read_bytes())
            output = root / 'build/generated'
            with patch.object(game, 'SOURCE', source):
                game.prepare(output)
                records = json.loads((output / 'source-bindings.json').read_text())
                self.assertEqual(set(records), game.INVENTORY)
                for relative in records:
                    self.assertEqual(records[relative]['source_sha256'], hashlib.sha256((source / relative).read_bytes()).hexdigest())
                extra = source / 'client/java/io/p2wlan/mc/game/Unreviewed.java'
                extra.write_text('package io.p2wlan.mc.game; class Unreviewed {}')
                with self.assertRaises(ContractError): game.prepare(root / 'build/rejected')
                self.assertFalse((root / 'build/rejected').exists()); extra.unlink()
                linked = root / 'build/link'; linked.symlink_to(output, target_is_directory=True)
                with self.assertRaisesRegex(ValueError, 'OUTPUT_LINK'): game.prepare(linked)
                with self.assertRaisesRegex(ValueError, 'NOT_GENERATED'): game.prepare(root / 'tracked')

    def test_nonblocking_connection_and_separate_dedicated_fixture_api(self):
        base = game.PROJECT / 'java/game-api-1.16.5/src'
        source = (base / 'client/java/io/p2wlan/mc/game/client/connect/MinecraftConnect.java').read_text()
        self.assertIn('ScopedClientConnect.open(connection, endpoint, prelude,', source)
        self.assertIn('ScopedClientConnect.wrap(connection, endpoint.address(), vanilla)', source)
        self.assertIn('.addLast("packet_handler", connection)', source)
        self.assertNotIn('syncUninterruptibly', source)
        self.assertNotIn('new Connection(', source)
        source = (base / 'client/java/io/p2wlan/mc/game/client/connect/GameLogin.java').read_text()
        self.assertNotIn('prepareForMultiplayer', source)
        self.assertIn('new ServerboundHelloPacket(client.getUser().getGameProfile())', source)
        source = (base / 'fixtureServer/java/io/p2wlan/mc/game/fixture/GameFixtureServerApi.java').read_text()
        self.assertNotIn('net.minecraft.client.', source)


if __name__ == '__main__': unittest.main()
