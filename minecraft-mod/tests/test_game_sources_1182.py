"""Guard the 1.18.2 API substitutions, not Minecraft runtime acceptance."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import game_sources_1182 as game
from source_contract import ContractError


class Game1182SourcesTests(unittest.TestCase):
    def test_component_factory_rewrite_preserves_comments_literals_and_arguments(self):
        source = '''package io.p2wlan.mc.game.client;
import net.minecraft.network.chat.Component;
class Probe {
// Component.literal("comment")
String text="Component.translatable(\\"literal\\")";
String block="""Component.literal("text-block")""";
Object first=Component.literal("a\\"b" + value());
Object second=net.minecraft.network.chat.Component.translatable("key", nested(1, 2));
}'''
        result, counts = game.component_factories(source)
        self.assertEqual(counts, {'literal': 1, 'translatable': 1})
        self.assertIn('// Component.literal("comment")', result)
        self.assertIn('String text="Component.translatable(\\"literal\\")";', result)
        self.assertIn('String block="""Component.literal("text-block")""";', result)
        self.assertIn(game.GAME_TEXT + '.literal("a\\"b" + value())', result)
        self.assertIn(game.GAME_TEXT + '.translatable("key", nested(1, 2))', result)
        with self.assertRaisesRegex(ContractError, 'unicode_escape'):
            game.tokens(r'\u0043omponent.literal("hidden-token")')

    def test_ui_conversion_delegates_effects_and_preserves_shared_decisions(self):
        relative = Path('client/java/io/p2wlan/mc/game/client/lan/VirtualLanEntry.java')
        source = (game.SOURCE / relative).read_text()
        result, _ = game.adapt(source, relative)
        self.assertIn('render(PoseStack draw,', result)
        self.assertIn(game.GAME_UI + '.fill(draw,', result)
        self.assertIn(game.GAME_UI + '.drawString(draw,', result)
        self.assertIn('GameUi.tooltip(screen, draw, mouseX, mouseY,', result)
        self.assertIn('ClientNetworkView.peer(service.peer())', result)
        self.assertNotIn('getServerNarration()', result)
        with self.assertRaisesRegex(ContractError, 'draw_inventory'):
            game.adapt(source.replace('draw.fill(', 'draw.unverifiedDraw('), relative)
        with self.assertRaises(ContractError):
            game.adapt(source.replace('GameUi.tooltip(screen,', 'GameUi.tooltip(screen, extra,'), relative)

    def test_password_narration_and_hint_semantics_remain_explicit(self):
        relative = Path('client/java/io/p2wlan/mc/game/client/screen/PasswordField.java')
        result, _ = game.adapt((game.SOURCE / relative).read_text(), relative)
        self.assertIn('public void updateNarration(', result)
        self.assertIn('builder.add(NarratedElementType.TITLE, getMessage())', result)
        self.assertIn('"*".repeat(value.length())', result)
        self.assertNotIn('getValue()', result)
        relative = Path('client/java/io/p2wlan/mc/game/client/screen/AccountScreen.java')
        result, _ = game.adapt((game.SOURCE / relative).read_text(), relative)
        self.assertEqual(result.count(game.GAME_UI + '.hint('), 3)
        self.assertNotIn('setSuggestion', result)
        api = (game.PROJECT / 'java/game-api-1.18.2/src/client/java/io/p2wlan/mc/game/client/GameUi.java').read_text()
        self.assertIn('public static void hint(EditBox field, Component text) {}', api)

    def test_rewrites_reject_new_calls_and_preserve_widget_tooltip_narration(self):
        relative = Path('client/java/io/p2wlan/mc/game/client/screen/LogoButton.java')
        source = (game.SOURCE / relative).read_text(); result, _ = game.adapt(source, relative)
        self.assertIn('extends ' + game.GAME_UI + '.TooltipButton', result)
        self.assertIn(game.GAME_UI + '.buttonTooltip(this,', result)
        self.assertNotIn('import net.minecraft.client.gui.components.Tooltip;', result)
        with self.assertRaisesRegex(ContractError, 'component_inventory'):
            game.adapt(source.replace('"P2WLAN"', '"P2WLAN" + Component.literal("extra")'), relative)
        with self.assertRaisesRegex(ContractError, 'draw_inventory'):
            game.adapt(source.replace('int edge=', 'draw.fill(1,2,3,4,5); int edge='), relative)
        api = (game.PROJECT / 'java/game-api-1.18.2/src/client/java/io/p2wlan/mc/game/client/GameUi.java').read_text()
        self.assertIn('private Component tooltip;', api)
        self.assertIn('output.add(NarratedElementType.HINT, tooltip)', api)
        self.assertIn('visible && isHoveredOrFocused() && tooltip != null', api)
        for name, count in (('AccountScreen', 2), ('RoomsScreen', 3)):
            relative = Path('client/java/io/p2wlan/mc/game/client/screen/' + name + '.java')
            source = (game.SOURCE / relative).read_text(); result, _ = game.adapt(source, relative)
            self.assertEqual(result.count('init(minecraft, width, height)'), count)
            with self.assertRaisesRegex(ContractError, 'rebuild_inventory'):
                game.adapt(source.replace('rebuildWidgets();', 'unexpectedSdk();', 1), relative)

    def test_exact_constructors_and_login_selectors_fail_closed(self):
        for name, tree, old, new in (
                ('MultiplayerListMixin', 'client', 'int y, int itemHeight', 'int top, int bottom, int itemHeight'),
                ('ServerLoginMixin', 'server', '"verifyLoginAndFinishConnectionSetup"', '"handleAcceptedLogin"')):
            relative = Path(tree + '/java/io/p2wlan/mc/game/' + ('mixin/' if tree == 'client' else 'server/mixin/') + name + '.java')
            source = (game.SOURCE / relative).read_text(); result, _ = game.adapt(source, relative)
            self.assertNotIn(old, result); self.assertIn(new, result)
            with self.assertRaises(ContractError): game.adapt(source.replace(old, 'unexpected_sdk'), relative)
        with self.assertRaisesRegex(ContractError, 'effect_owner'):
            game.adapt('package io.p2wlan.mc.game.client.connect;', Path(next(iter(game.EXCLUDED))))
        with self.assertRaisesRegex(ContractError, 'source_inventory'):
            game.adapt('package io.p2wlan.mc.game;', Path('client/java/unknown.java'))

    def test_generation_binds_the_unique_shared_source_and_rejects_links_inventory_drift(self):
        with tempfile.TemporaryDirectory(dir=game.PROJECT / '.evidence') as temporary:
            root = Path(temporary); source = root / 'sources'; source.mkdir()
            for relative in game.INVENTORY:
                path = source / relative; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes((game.SOURCE / relative).read_bytes())
            output = root / 'build/generated'
            with patch.object(game, 'SOURCE', source):
                game.prepare(output)
                records = json.loads((output / 'source-bindings.json').read_text())
                self.assertEqual(set(records), game.INVENTORY)
                self.assertEqual(set(str(p.relative_to(output)) for p in output.rglob('*.java')), game.INVENTORY)
                for relative in game.INVENTORY:
                    self.assertEqual(records[relative]['source_sha256'], hashlib.sha256((source / relative).read_bytes()).hexdigest())
                extra = source / 'client/java/io/p2wlan/mc/game/Extra.java'; extra.write_text('package io.p2wlan.mc.game; class Extra {}')
                rejected = root / 'build/rejected'
                with self.assertRaises(ContractError): game.prepare(rejected)
                self.assertFalse(rejected.exists()); extra.unlink()
                linked = root / 'build/linked'; linked.parent.mkdir(exist_ok=True); linked.symlink_to(output, target_is_directory=True)
                with self.assertRaisesRegex(ValueError, 'OUTPUT_LINK'): game.prepare(linked)
                with self.assertRaisesRegex(ValueError, 'NOT_GENERATED'): game.prepare(root / 'tracked')

    def test_nonblocking_bootstrap_uses_the_original_connection_and_shared_fence(self):
        source = (game.PROJECT / 'java/game-api-1.18.2/src/client/java/io/p2wlan/mc/game/client/connect/MinecraftConnect.java').read_text()
        self.assertIn('ScopedClientConnect.open(connection, endpoint, prelude,', source)
        self.assertIn('ScopedClientConnect.wrap(connection, endpoint.address(), vanilla)', source)
        self.assertIn('.addLast("packet_handler", connection)', source)
        self.assertIn('.connect(endpoint.address())', source)
        self.assertNotIn('syncUninterruptibly', source)
        self.assertNotIn('new Connection(', source)
        self.assertNotIn('new NioEventLoopGroup', source)

    def test_old_disconnect_diagnostic_uses_version_effect_and_preserves_failure_contract(self):
        relative = Path('localGameClientFixture/java/io/p2wlan/mc/game/fixture/GameClientChecks.java')
        source = (game.SOURCE / relative).read_text()
        result, _ = game.adapt(source, relative)
        self.assertIn('var text = GameFixtureApi.disconnectedReason(client.screen);', result)
        self.assertNotIn('getDeclaredField("reason")', result)
        self.assertIn('Math.min(1024, text.getString().length())', result)
        self.assertIn('throw new IllegalStateException("VANILLA_DISCONNECTED")', result)
        self.assertEqual(result.count('data.check('), source.count('data.check('))
        with self.assertRaisesRegex(ContractError, 'fixture_disconnect_reason'):
            game.adapt(source.replace('getDeclaredField("reason")', 'getDeclaredField("changed")'), relative)


if __name__ == '__main__': unittest.main()
