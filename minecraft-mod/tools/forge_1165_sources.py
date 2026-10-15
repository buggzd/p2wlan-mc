#!/usr/bin/env python3
"""Project guarded 1.16.5 game sources into Forge's exact MCP class namespace."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile

import game_sources_1165 as game
from game_sources_1182 import Token, apply, tokens
from source_contract import ContractError

PROJECT = game.PROJECT
API = PROJECT / 'java/game-api-1.16.5/src'
MAX_SOURCE = 65536
# Pinned Mojang 1.16.5 class names joined to MCP 20210115.111550 class names,
# verified against Forge 36.2.42's actual mapped_official named SDK inventory.
CLASS_ALIASES = {'com.mojang.blaze3d.vertex.PoseStack': 'com.mojang.blaze3d.matrix.MatrixStack',
 'net.minecraft.SharedConstants': 'net.minecraft.util.SharedConstants',
 'net.minecraft.Util': 'net.minecraft.util.Util',
 'net.minecraft.client.Minecraft': 'net.minecraft.client.Minecraft',
 'net.minecraft.client.Screenshot': 'net.minecraft.util.ScreenShotHelper',
 'net.minecraft.client.gui.Font': 'net.minecraft.client.gui.FontRenderer',
 'net.minecraft.client.gui.GuiComponent': 'net.minecraft.client.gui.AbstractGui',
 'net.minecraft.client.gui.chat.NarratorChatListener': 'net.minecraft.client.gui.chat.NarratorChatListener',
 'net.minecraft.client.gui.components.AbstractWidget': 'net.minecraft.client.gui.widget.Widget',
 'net.minecraft.client.gui.components.Button': 'net.minecraft.client.gui.widget.button.Button',
 'net.minecraft.client.gui.components.Button$OnPress': 'net.minecraft.client.gui.widget.button.Button$IPressable',
 'net.minecraft.client.gui.components.Button$OnTooltip': 'net.minecraft.client.gui.widget.button.Button$ITooltip',
 'net.minecraft.client.gui.components.EditBox': 'net.minecraft.client.gui.widget.TextFieldWidget',
 'net.minecraft.client.gui.components.ObjectSelectionList': 'net.minecraft.client.gui.widget.list.ExtendedList',
 'net.minecraft.client.gui.screens.ConnectScreen': 'net.minecraft.client.gui.screen.ConnectingScreen',
 'net.minecraft.client.gui.screens.DisconnectedScreen': 'net.minecraft.client.gui.screen.DisconnectedScreen',
 'net.minecraft.client.gui.screens.PauseScreen': 'net.minecraft.client.gui.screen.IngameMenuScreen',
 'net.minecraft.client.gui.screens.Screen': 'net.minecraft.client.gui.screen.Screen',
 'net.minecraft.client.gui.screens.TitleScreen': 'net.minecraft.client.gui.screen.MainMenuScreen',
 'net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen': 'net.minecraft.client.gui.screen.MultiplayerScreen',
 'net.minecraft.client.gui.screens.multiplayer.ServerSelectionList': 'net.minecraft.client.gui.screen.ServerSelectionList',
 'net.minecraft.client.gui.screens.multiplayer.ServerSelectionList$Entry': 'net.minecraft.client.gui.screen.ServerSelectionList$Entry',
 'net.minecraft.client.gui.screens.multiplayer.ServerSelectionList$NetworkServerEntry': 'net.minecraft.client.gui.screen.ServerSelectionList$LanDetectedEntry',
 'net.minecraft.client.gui.screens.multiplayer.ServerSelectionList$OnlineServerEntry': 'net.minecraft.client.gui.screen.ServerSelectionList$NormalEntry',
 'net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl': 'net.minecraft.client.network.login.ClientLoginNetHandler',
 'net.minecraft.client.multiplayer.ClientPacketListener': 'net.minecraft.client.network.play.ClientPlayNetHandler',
 'net.minecraft.client.multiplayer.ServerData': 'net.minecraft.client.multiplayer.ServerData',
 'net.minecraft.client.server.IntegratedServer': 'net.minecraft.server.integrated.IntegratedServer',
 'net.minecraft.client.server.LanServer': 'net.minecraft.client.network.LanServerInfo',
 'net.minecraft.commands.CommandSource': 'net.minecraft.command.ICommandSource',
 'net.minecraft.commands.CommandSourceStack': 'net.minecraft.command.CommandSource',
 'net.minecraft.commands.Commands': 'net.minecraft.command.Commands',
 'net.minecraft.core.Registry': 'net.minecraft.util.registry.Registry',
 'net.minecraft.core.RegistryAccess': 'net.minecraft.util.registry.DynamicRegistries',
 'net.minecraft.core.RegistryAccess$RegistryHolder': 'net.minecraft.util.registry.DynamicRegistries$Impl',
 'net.minecraft.network.Connection': 'net.minecraft.network.NetworkManager',
 'net.minecraft.network.ConnectionProtocol': 'net.minecraft.network.ProtocolType',
 'net.minecraft.network.PacketDecoder': 'net.minecraft.network.NettyPacketDecoder',
 'net.minecraft.network.PacketEncoder': 'net.minecraft.network.NettyPacketEncoder',
 'net.minecraft.network.Varint21FrameDecoder': 'net.minecraft.network.NettyVarint21FrameDecoder',
 'net.minecraft.network.Varint21LengthFieldPrepender': 'net.minecraft.network.NettyVarint21FrameEncoder',
 'net.minecraft.network.chat.ChatType': 'net.minecraft.util.text.ChatType',
 'net.minecraft.network.chat.Component': 'net.minecraft.util.text.ITextComponent',
 'net.minecraft.network.chat.MutableComponent': 'net.minecraft.util.text.IFormattableTextComponent',
 'net.minecraft.network.chat.Style': 'net.minecraft.util.text.Style',
 'net.minecraft.network.chat.TextComponent': 'net.minecraft.util.text.StringTextComponent',
 'net.minecraft.network.chat.TranslatableComponent': 'net.minecraft.util.text.TranslationTextComponent',
 'net.minecraft.network.protocol.PacketFlow': 'net.minecraft.network.PacketDirection',
 'net.minecraft.network.protocol.handshake.ClientIntentionPacket': 'net.minecraft.network.handshake.client.CHandshakePacket',
 'net.minecraft.network.protocol.login.ServerboundHelloPacket': 'net.minecraft.network.login.client.CLoginStartPacket',
 'net.minecraft.server.MinecraftServer': 'net.minecraft.server.MinecraftServer',
 'net.minecraft.server.level.ServerPlayer': 'net.minecraft.entity.player.ServerPlayerEntity',
 'net.minecraft.server.network.ServerConnectionListener': 'net.minecraft.network.NetworkSystem',
 'net.minecraft.server.network.ServerLoginPacketListenerImpl': 'net.minecraft.network.login.ServerLoginNetHandler',
 'net.minecraft.server.players.PlayerList': 'net.minecraft.server.management.PlayerList',
 'net.minecraft.util.FormattedCharSequence': 'net.minecraft.util.IReorderingProcessor',
 'net.minecraft.world.Difficulty': 'net.minecraft.world.Difficulty',
 'net.minecraft.world.level.DataPackConfig': 'net.minecraft.util.datafix.codec.DatapackCodec',
 'net.minecraft.world.level.GameRules': 'net.minecraft.world.GameRules',
 'net.minecraft.world.level.GameType': 'net.minecraft.world.GameType',
 'net.minecraft.world.level.LevelSettings': 'net.minecraft.world.WorldSettings',
 'net.minecraft.world.level.levelgen.FlatLevelSource': 'net.minecraft.world.gen.FlatChunkGenerator',
 'net.minecraft.world.level.levelgen.WorldGenSettings': 'net.minecraft.world.gen.settings.DimensionGeneratorSettings',
 'net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings': 'net.minecraft.world.gen.FlatGenerationSettings'}
INPUT_SHA256 = {'api/server/java/io/p2wlan/mc/game/server/mixin/ConsoleSourceMixin.java': '136df407391d84a56536d4f55b6cd7749b11f1c54a89e19d2c013e8102f5e132',
'api/client/java/io/p2wlan/mc/game/client/GameUi.java': '45a922b40d35fb83a87cba8c4f0116dc5575f79f1f857c594be26481a789cc9e',
 'api/client/java/io/p2wlan/mc/game/client/connect/GameLogin.java': '4db725b5c5076d64b3f37f7cc3d4b3974102c41ee716476ff1e812fef8ca7f75',
 'api/client/java/io/p2wlan/mc/game/client/connect/MinecraftConnect.java': '2953662731d07f003834ec3d9e81d68bdb60c167f854b9923dbe7d9450f3e1d1',
 'api/client/java/io/p2wlan/mc/game/mixin/ClientConnectionMixin.java': 'cc4b0b0983d3b1e8179d98de039a402a5efed2605ba592eca9d1ae4f14a147e6',
 'api/client/java/io/p2wlan/mc/game/mixin/LanNarrationMixin.java': 'ac854b7eb42336f9255de248950617fc696279ac02f73c995ea511de0ac09b2e',
 'api/fixture/java/io/p2wlan/mc/game/fixture/GameFixtureApi.java': 'f6263d29baf4be369d01557a79b9a94077374722fcc8a40d75b86ed7b23ee885',
 'api/fixtureServer/java/io/p2wlan/mc/game/fixture/GameFixtureServerApi.java': '0e68199309445bfee489eda3c657f0936042040733930fd284e3129b0ee25f66',
 'api/main/java/io/p2wlan/mc/game/GameText.java': '0703880a0d20096fddf37106251f19464f554252df483ac846fdc136a4cfafb4',
 'api/server/java/io/p2wlan/mc/game/server/GameCommandApi.java': '665d28703d15de570885df6f9c579c86c0ede2099fbbef4b16e7b7bd440cc2ac',
 'shared/client/java/io/p2wlan/mc/game/client/ClientDisplaySettings.java': '9ca1ce59e911b6182e5fa36f6a94c9da0977e8a5e72ddfa7d38f41d16b640dec',
 'shared/client/java/io/p2wlan/mc/game/client/ClientPlatform.java': '67c481a25303daf6b26ccfbc1d44597c297c467c9a3877f96717981783510bae',
 'shared/client/java/io/p2wlan/mc/game/client/LatencyHud.java': '47f81342ea9fa63712d89f66ab6038dcc60a1c21e087c48834a2983920a96117',
 'shared/client/java/io/p2wlan/mc/game/client/connect/GameJoins.java': '373079b4c83c113da4f8059092192bcb7fd1abfe25340e51b9a3bbb232dd16f1',
 'shared/client/java/io/p2wlan/mc/game/client/lan/ClientNetworkView.java': 'b80f5bb94db2ba31a97f291c0b8c1e42e68b056aeb56ad5d243bdcc2292dee47',
 'shared/client/java/io/p2wlan/mc/game/client/lan/VanillaLanList.java': '61db303b8a2276399f8e7d2ff18855c98a5a848799132a9e413563ce44089bc1',
 'shared/client/java/io/p2wlan/mc/game/client/lan/VanillaLanScreen.java': '01874a32dbf0fab68023fc79ea5bcf69949eca169433e8ebdb0f667bf906f500',
 'shared/client/java/io/p2wlan/mc/game/client/lan/VirtualLanEntry.java': '97d154540ab616cebdfb5dbbca61ce1705a4bd261c1c61efc0fbc6d9289bccae',
 'shared/client/java/io/p2wlan/mc/game/client/screen/AccountScreen.java': '33436ad4aad38c9fe003c5e6e8ba3ab504b2441c7231822a79c7e4e0e88b9ae2',
 'shared/client/java/io/p2wlan/mc/game/client/screen/AgentScreen.java': 'b8846484e0fc58a0c4b1a4649758ec9c962cdea86121d32b050b50f37bf8696f',
 'shared/client/java/io/p2wlan/mc/game/client/screen/DiagnosticsScreen.java': 'e0f4843f494294f5291db50e0b6f957ec5b501123f3171bf3708976acd42f760',
 'shared/client/java/io/p2wlan/mc/game/client/screen/DisplaySettingsScreen.java': '1ea92e3d22894e4281c4cb71d8cc8af009355606c2c72c02d8a78aa846936887',
 'shared/client/java/io/p2wlan/mc/game/client/screen/JoinGameScreen.java': 'cbf0a7205d4a26606d026073f6d7916e70cc745a6918bf7b346bdc6f112eea55',
 'shared/client/java/io/p2wlan/mc/game/client/screen/LatencyStatsScreen.java': '6dfcd12d54479db0566d18c91146a0cdec485d58c49233191c2b654ce85795e9',
 'shared/client/java/io/p2wlan/mc/game/client/screen/LocalPanelScreen.java': 'e7f42210dade90c948e151ff499245833df89b1c80128a7225122ea20de776b5',
 'shared/client/java/io/p2wlan/mc/game/client/screen/LogoButton.java': 'e0dd6e01b6648e9e8214bbb7e7682b5e9e0d27d8118e676ec6f0cda2719a1492',
 'shared/client/java/io/p2wlan/mc/game/client/screen/MenuLogo.java': 'dfe8b47de683764f8107187e9b4fbeac5a48c1298c5050500730b03e0828eff3',
 'shared/client/java/io/p2wlan/mc/game/client/screen/PasswordField.java': '65f8c7bcffd776cfc7d6d8ebb1516382ce73dfc9d9ab761a2afe03105e593250',
 'shared/client/java/io/p2wlan/mc/game/client/screen/PixelButton.java': '6e51d721eaba79a121c40f08f8cec3e0b97c457fbe7a584aceffedc619a6ad0c',
 'shared/client/java/io/p2wlan/mc/game/client/screen/RoomFormScreen.java': 'ab8ad494ef27736d764278aa093406088bcd9247e32080393431218ec0c527ab',
 'shared/client/java/io/p2wlan/mc/game/client/screen/RoomsScreen.java': '4454e4087364ad1830e566ac595caa1aa2726ad55a54f19e04efbf4c9caa4bb3',
 'shared/client/java/io/p2wlan/mc/game/client/screen/RuntimeScreen.java': '7f35a7f262599a1f799d2ead0173266b46e80bf1024aa5bc6bbbc4c1e95516bb',
 'shared/client/java/io/p2wlan/mc/game/mixin/IntegratedServerMixin.java': '9e563ff943ab693370d4037ad1bde140cc61231b4bee88954b6a81941c6a1390',
 'shared/client/java/io/p2wlan/mc/game/mixin/MultiplayerListAccessor.java': '2405dfc6cbbfddcbaf76b406cc8286303dbedb67aa7c8c3511c2983dda274d66',
 'shared/client/java/io/p2wlan/mc/game/mixin/MultiplayerListMixin.java': 'b1a3c129685eb752119bd97523e8306b95fbd47c8f461699d022d4a781c4db87',
 'shared/client/java/io/p2wlan/mc/game/mixin/MultiplayerScreenMixin.java': '16823f77c1c06a74fac66fa1e318dd9df28b8aabba9025c7e09813a2b34f0fdb',
 'shared/clientFixture/java/io/p2wlan/mc/game/fixture/ClientHookChecks.java': '006d9be962a0355604730dbedf5353d30f93bc93731d162dc581cd949b05edba',
 'shared/fixtureCommon/java/io/p2wlan/mc/game/fixture/GameFixtureData.java': '36bcce687ab27f449e30bff126d1abbaeed760c7320daed30bd28b689bec6845',
 'shared/fixtureCommon/java/io/p2wlan/mc/game/fixture/InstalledArtifact.java': '1ab56334b131a95e695a4053c34d5cc1eaf0ca03f564d0fb4e098a7207967fae',
 'shared/integratedFixture/java/io/p2wlan/mc/game/fixture/IntegratedHostChecks.java': 'f531099bde8a6f47c0c0612fdd117a2e78de296e573643dd71f1d1629c56a265',
 'shared/localGameClientFixture/java/io/p2wlan/mc/game/fixture/GameClientChecks.java': 'fc209ace7f7c105e66246abbf8d145e42f7328bb14122f8f292eb7b0ebe6805f',
 'shared/localGameFixture/java/io/p2wlan/mc/game/fixture/GameServerChecks.java': '9894f72dded7bdd940a97d5207ad450117575aba9a6cfd125a483363d154f70b',
 'shared/server/java/io/p2wlan/mc/game/server/WorldCommands.java': '27d26b794f7fa2d0c913741bc2f4c7a8eeb6dacb5979920f328fd7b98607a451',
 'shared/server/java/io/p2wlan/mc/game/server/mixin/ServerConnectionMixin.java': '71c99c2574a26a6eba0c354c39ef3a12982ae7daaf11094fa146e987fda768d3',
 'shared/server/java/io/p2wlan/mc/game/server/mixin/ServerLoginConnectionMixin.java': 'f57aa4b0f5c9b7f3ef71e5095d8d34bf922284cc79fa324208a83e9f2713e21e',
 'shared/server/java/io/p2wlan/mc/game/server/mixin/ServerLoginMixin.java': '7974be24f4f01592834a5c255596414adb0d5226b0000a9fd423fda9e0a695b3'}
API_INVENTORY = frozenset(('client/java/io/p2wlan/mc/game/client/GameUi.java',
 'client/java/io/p2wlan/mc/game/client/connect/GameLogin.java',
 'client/java/io/p2wlan/mc/game/client/connect/MinecraftConnect.java',
 'client/java/io/p2wlan/mc/game/mixin/ClientConnectionMixin.java',
 'client/java/io/p2wlan/mc/game/mixin/LanNarrationMixin.java',
 'fixture/java/io/p2wlan/mc/game/fixture/GameFixtureApi.java',
 'fixtureServer/java/io/p2wlan/mc/game/fixture/GameFixtureServerApi.java',
 'main/java/io/p2wlan/mc/game/GameText.java',
 'server/java/io/p2wlan/mc/game/server/GameCommandApi.java',
 'server/java/io/p2wlan/mc/game/server/mixin/ConsoleSourceMixin.java'))


def _java(value):
    return value.replace('$', '.')


def _pattern(value):
    return tuple(re.findall(r'[A-Za-z_$][A-Za-z0-9_$]*|\.', _java(value)))


def rewrite(source):
    """Change declared SDK type symbols and exact descriptors, never members."""
    items = tokens(source); imports = {}; imported_ranges = set()
    aliases = {_java(name): _java(target) for name, target in CLASS_ALIASES.items()}
    for i, token in enumerate(items):
        if token.value != 'import': continue
        end = i + 1
        while end < len(items) and items[end].value != ';': end += 1
        if end == len(items): raise ContractError('forge1165_import_syntax')
        parts = [t.value for t in items[i + 1:end]]
        if parts and parts[0] == 'static': parts = parts[1:]
        qualified = ''.join(parts)
        if not qualified.startswith(('net.minecraft.', 'com.mojang.blaze3d.')): continue
        if qualified not in aliases: raise ContractError('forge1165_import_type')
        simple = qualified.rsplit('.', 1)[-1]
        if simple in imports: raise ContractError('forge1165_import_collision')
        imports[simple] = qualified
        imported_ranges.update(range(i, end + 1))
    patterns = {name: _pattern(name) for name in aliases}
    # A fully qualified class has its own exact replacement. Imported nested
    # types are qualified by that declared outer type, not inferred by suffix.
    short = {}
    for simple, original in imports.items():
        short[simple] = (original, aliases[original].rsplit('.', 1)[-1])
        for name in aliases:
            if name.startswith(original + '.'):
                spelling = simple + name[len(original):]
                if not aliases[name].startswith(aliases[original] + '.'): raise ContractError('forge1165_nested_owner')
                short[spelling] = (name, aliases[original].rsplit('.', 1)[-1] + '.' + aliases[name][len(aliases[original]) + 1:])
    # These three reviewed subclasses inherit Button's public nested callback
    # types without qualifying them. No other unqualified nested type is mapped.
    button = imports.get('Button')
    if button == 'net.minecraft.client.gui.components.Button':
        for inherited in ('OnPress', 'OnTooltip'):
            original = button + '.' + inherited
            if original in aliases: short[inherited] = (original, aliases[original].rsplit('.', 1)[-1])
    short_patterns = {key: _pattern(key) for key in short}
    counts = {}; edits = []; cursor = 0
    ordered = sorted(patterns, key=lambda name: len(patterns[name]), reverse=True)
    ordered_short = sorted(short, key=lambda name: len(short_patterns[name]), reverse=True)
    while cursor < len(items):
        matched = None
        for name in ordered:
            pattern = patterns[name]
            if tuple(t.value for t in items[cursor:cursor + len(pattern)]) == pattern:
                matched = (name, len(pattern), aliases[name]); break
        if matched is None and cursor not in imported_ranges:
            for spelling in ordered_short:
                pattern = short_patterns[spelling]
                if tuple(t.value for t in items[cursor:cursor + len(pattern)]) == pattern:
                    if cursor > 0 and items[cursor - 1].value == '.': continue
                    original, target = short[spelling]
                    matched = (original, len(pattern), target); break
        if matched is not None:
            original, size, target = matched
            counts[original] = counts.get(original, 0) + 1
            start, end = items[cursor].start, items[cursor + size - 1].end
            if source[start:end] != target: edits.append((start, end, target))
            cursor += size; continue
        if items[cursor].value == 'net' and tuple(t.value for t in items[cursor:cursor + 3]) == ('net', '.', 'minecraft'):
            raise ContractError('forge1165_qualified_type')
        if tuple(t.value for t in items[cursor:cursor + 5]) == ('com', '.', 'mojang', '.', 'blaze3d'):
            raise ContractError('forge1165_qualified_type')
        token = items[cursor]
        if token.value == '<literal>' and source[token.start:token.start + 1] == '"' \
                and not source.startswith('"""', token.start):
            literal = json.loads(source[token.start:token.end])
            descriptors = re.findall(r'L((?:net/minecraft/|com/mojang/blaze3d/)[A-Za-z0-9_$/]+);', literal)
            if descriptors:
                converted = literal
                for internal in set(descriptors):
                    original = internal.replace('/', '.')
                    if original not in CLASS_ALIASES: raise ContractError('forge1165_descriptor_type')
                    counts[_java(original)] = counts.get(_java(original), 0) + descriptors.count(internal)
                    converted = converted.replace('L' + internal + ';', 'L' + CLASS_ALIASES[original].replace('.', '/') + ';')
                if converted != literal: edits.append((token.start, token.end, json.dumps(converted)))
        cursor += 1
    return apply(source, edits), counts


def inputs():
    result = {}
    for relative in sorted(game.INVENTORY):
        path = game.SOURCE / relative
        if path.is_symlink() or not stat.S_ISREG(path.stat().st_mode) or not 0 < path.stat().st_size <= MAX_SOURCE:
            raise ContractError('forge1165_source_owner')
        raw = path.read_bytes()
        converted, _ = game.adapt(raw.decode('utf-8'), relative)
        result['shared/' + relative] = (converted, hashlib.sha256(raw).hexdigest())
    actual = set()
    for path in sorted(API.rglob('*.java')):
        relative = path.relative_to(API).as_posix(); actual.add(relative)
        if path.is_symlink() or not stat.S_ISREG(path.stat().st_mode) or not 0 < path.stat().st_size <= MAX_SOURCE:
            raise ContractError('forge1165_source_owner')
        raw = path.read_bytes(); result['api/' + relative] = (raw.decode('utf-8'), hashlib.sha256(raw).hexdigest())
    if actual != API_INVENTORY: raise ContractError('forge1165_api_inventory')
    return result


def prepare(output):
    output = Path(output)
    if not output.is_absolute() or '..' in output.parts or not output.is_relative_to(PROJECT) \
            or 'build' not in output.relative_to(PROJECT).parts:
        raise ValueError('FORGE1165_OUTPUT_NOT_GENERATED')
    source = inputs(); pending = []; records = {}
    if set(source) != set(INPUT_SHA256): raise ContractError('forge1165_input_inventory')
    for relative, (text, raw_digest) in source.items():
        digest = hashlib.sha256(text.encode('utf-8')).hexdigest()
        if not 0 < len(text.encode('utf-8')) <= MAX_SOURCE or digest != INPUT_SHA256[relative]:
            raise ContractError('forge1165_input_changed')
        converted, aliases = rewrite(text)
        target = output / relative
        if any(p.is_symlink() for p in (target, *target.parents) if p.is_relative_to(PROJECT)):
            raise ValueError('FORGE1165_OUTPUT_LINK')
        pending.append((target, converted.encode('utf-8')))
        records[relative] = {'original_sha256': raw_digest, 'game1165_sha256': digest,
                             'mcp_sha256': hashlib.sha256(converted.encode('utf-8')).hexdigest(),
                             'class_aliases': aliases}
    if (output / 'source-bindings.json').is_symlink(): raise ValueError('FORGE1165_OUTPUT_LINK')
    for target, content in pending:
        target.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(prefix='forge1165-', dir=target.parent)
        try:
            with os.fdopen(descriptor, 'wb') as stream: stream.write(content)
            os.replace(temporary, target)
        finally: Path(temporary).unlink(missing_ok=True)
    (output / 'source-bindings.json').write_text(json.dumps(records, sort_keys=True, indent=2) + '\n')
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try: prepare(args.output)
    except (OSError, ValueError, ContractError) as error: parser.exit(1, str(error) + '\n')
    print('PASS Forge 1.16.5 exact MCP class-source projection')


if __name__ == '__main__': main()
