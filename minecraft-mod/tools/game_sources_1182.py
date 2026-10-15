#!/usr/bin/env python3
"""Generate guarded Minecraft 1.18.2 API substitutions from the shared source."""
import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile

from source_contract import ContractError, replace_once

PROJECT = Path(__file__).resolve().parents[1]
SOURCE = PROJECT / 'java/game-1.21.1/src'
MAX_SOURCE = 65536
GAME_UI = 'io.p2wlan.mc.game.client.GameUi'
GAME_TEXT = 'io.p2wlan.mc.game.GameText'
TREES = ('client', 'server', 'fixtureCommon', 'localGameFixture', 'localGameClientFixture', 'integratedFixture', 'clientFixture')
EXCLUDED = frozenset(('client/java/io/p2wlan/mc/game/client/connect/MinecraftConnect.java',
                      'client/java/io/p2wlan/mc/game/mixin/ClientConnectionMixin.java'))
GUI_FILES = frozenset(('LatencyHud', 'VirtualLanEntry', 'AccountScreen', 'AgentScreen', 'DiagnosticsScreen',
                      'DisplaySettingsScreen', 'JoinGameScreen', 'LatencyStatsScreen', 'LocalPanelScreen',
                      'LogoButton', 'PixelButton', 'RuntimeScreen'))
TOOLTIPS = {'VirtualLanEntry': 'draw', 'DiagnosticsScreen': 'draw', 'RuntimeScreen': 'draw',
            'LatencyStatsScreen': 'draw', 'AgentScreen': 'context'}
HINTS = {'AgentScreen': 1, 'AccountScreen': 3, 'RoomFormScreen': 1}
COORDINATES = {'LogoButton', 'PixelButton', 'MenuLogo', 'GameClientChecks'}
COMPONENT_COUNTS = {
    'GameJoins': (3, 0), 'VirtualLanEntry': (2, 0), 'AccountScreen': (3, 0), 'AgentScreen': (13, 0),
    'DiagnosticsScreen': (1, 0), 'DisplaySettingsScreen': (5, 0), 'JoinGameScreen': (5, 0),
    'LatencyStatsScreen': (1, 0), 'LocalPanelScreen': (3, 0), 'LogoButton': (2, 0), 'MenuLogo': (0, 1),
    'RoomFormScreen': (1, 0), 'RuntimeScreen': (1, 0), 'MultiplayerScreenMixin': (2, 0),
    'ClientHookChecks': (1, 0), 'IntegratedHostChecks': (1, 0), 'GameClientChecks': (0, 1),
    'GameServerChecks': (1, 0), 'WorldCommands': (2, 0), 'ServerLoginMixin': (1, 0),
}
# GuiGraphics tokens, then draw/context calls to fill, string, centered string.
DRAW_COUNTS = {
    'LatencyHud': (2, 1, 1, 0), 'VirtualLanEntry': (2, 3, 5, 0), 'AccountScreen': (2, 0, 2, 0),
    'AgentScreen': (3, 4, 7, 1), 'DiagnosticsScreen': (3, 0, 1, 0), 'DisplaySettingsScreen': (2, 0, 2, 0),
    'JoinGameScreen': (2, 0, 0, 2), 'LatencyStatsScreen': (3, 1, 2, 0), 'LocalPanelScreen': (2, 2, 3, 0),
    'LogoButton': (3, 10, 0, 0), 'PixelButton': (2, 5, 0, 1), 'RuntimeScreen': (3, 1, 2, 0),
}
COORDINATE_COUNTS = {'LogoButton': 2, 'PixelButton': 2, 'MenuLogo': 2, 'GameClientChecks': 14}
INVENTORY = frozenset(
    'client/java/io/p2wlan/mc/game/' + path + '.java' for path in (
        'client/ClientDisplaySettings', 'client/ClientPlatform', 'client/LatencyHud', 'client/connect/GameJoins',
        'client/lan/ClientNetworkView', 'client/lan/VanillaLanList', 'client/lan/VanillaLanScreen', 'client/lan/VirtualLanEntry',
        'client/screen/AccountScreen', 'client/screen/AgentScreen', 'client/screen/DiagnosticsScreen',
        'client/screen/DisplaySettingsScreen', 'client/screen/JoinGameScreen', 'client/screen/LatencyStatsScreen',
        'client/screen/LocalPanelScreen', 'client/screen/LogoButton', 'client/screen/MenuLogo', 'client/screen/PasswordField',
        'client/screen/PixelButton', 'client/screen/RoomFormScreen', 'client/screen/RoomsScreen', 'client/screen/RuntimeScreen',
        'mixin/IntegratedServerMixin', 'mixin/MultiplayerListAccessor', 'mixin/MultiplayerListMixin', 'mixin/MultiplayerScreenMixin')) \
    | frozenset('server/java/io/p2wlan/mc/game/server/' + path + '.java' for path in (
        'WorldCommands', 'mixin/ServerConnectionMixin', 'mixin/ServerLoginConnectionMixin', 'mixin/ServerLoginMixin')) \
    | frozenset(tree + '/java/io/p2wlan/mc/game/fixture/' + name + '.java' for tree, name in (
        ('fixtureCommon', 'GameFixtureData'), ('fixtureCommon', 'InstalledArtifact'),
        ('localGameFixture', 'GameServerChecks'), ('localGameClientFixture', 'GameClientChecks'),
        ('integratedFixture', 'IntegratedHostChecks'), ('clientFixture', 'ClientHookChecks')))


@dataclass(frozen=True)
class Token:
    value: str
    start: int
    end: int
    identifier: bool = False


def tokens(source):
    """Keep comments, string/char literals and text blocks outside rewrite syntax."""
    if len(source.encode('utf-8')) > MAX_SOURCE:
        raise ContractError('game1182_source_capacity')
    if re.search(r'\\u+[0-9a-fA-F]{4}', source):
        raise ContractError('game1182_source_unicode_escape')
    result = []
    i = 0
    while i < len(source):
        if source[i].isspace():
            i += 1
        elif source.startswith('//', i):
            end = source.find('\n', i + 2); i = len(source) if end < 0 else end + 1
        elif source.startswith('/*', i):
            end = source.find('*/', i + 2)
            if end < 0: raise ContractError('game1182_source_comment')
            i = end + 2
        elif source.startswith('"""', i):
            end = i + 3
            while end < len(source):
                if source.startswith('"""', end): break
                end += 2 if source[end] == '\\' else 1
            if end >= len(source): raise ContractError('game1182_source_literal')
            result.append(Token('<literal>', i, end + 3)); i = end + 3
        elif source[i] in ('"', "'"):
            quote = source[i]; start = i; i += 1
            while i < len(source) and source[i] != quote:
                i += 2 if source[i] == '\\' else 1
            if i >= len(source): raise ContractError('game1182_source_literal')
            i += 1; result.append(Token('<literal>', start, i))
        elif source[i].isalpha() or source[i] in '_$':
            start = i; i += 1
            while i < len(source) and (source[i].isalnum() or source[i] in '_$'): i += 1
            result.append(Token(source[start:i], start, i, True))
        else:
            result.append(Token(source[i], i, i + 1)); i += 1
    return result


def apply(source, replacements):
    previous = len(source)
    for start, end, value in sorted(replacements, reverse=True):
        if not 0 <= start < end <= previous: raise ContractError('game1182_source_overlap')
        source = source[:start] + value + source[end:]; previous = start
    return source


def close_paren(items, index):
    depth = 0
    for cursor in range(index, len(items)):
        if items[cursor].value == '(': depth += 1
        elif items[cursor].value == ')':
            depth -= 1
            if depth == 0: return cursor
    raise ContractError('game1182_source_parentheses')


def component_factories(source):
    items = tokens(source); edits = []; counts = {'literal': 0, 'translatable': 0}; i = 0
    qualified = ['net', '.', 'minecraft', '.', 'network', '.', 'chat', '.', 'Component']
    imported = 'import net.minecraft.network.chat.Component;' in source
    while i < len(items):
        start = i
        if [t.value for t in items[i:i + len(qualified)]] == qualified:
            component = i + len(qualified) - 1
        elif items[i].value == 'Component' and (i == 0 or items[i - 1].value != '.'):
            component = i
        else:
            i += 1; continue
        tail = [t.value for t in items[component + 1:component + 4]]
        if len(tail) == 3 and tail[0] == '.' and tail[2] == '(' and tail[1] in counts:
            if component == i and not imported: raise ContractError('game1182_component_owner')
            counts[tail[1]] += 1
            edits.append((items[start].start, items[component + 2].end, GAME_TEXT + '.' + tail[1]))
            i = component + 4
        else: i = component + 1
    return apply(source, edits), counts


def gui(source, name):
    original = tokens(source)
    draws = tuple(sum([t.value for t in original[i:i + 4]] in ([receiver, '.', method, '('] for receiver in ('draw', 'context'))
                      for i in range(len(original))) for method in ('fill', 'drawString', 'drawCenteredString'))
    if (sum(t.value == 'GuiGraphics' for t in original), *draws) != DRAW_COUNTS.get(name, (0, 0, 0, 0)):
        raise ContractError('game1182_draw_inventory')
    coordinates = sum([t.value for t in original[i:i + 3]] in (['getX', '(', ')'], ['getY', '(', ')']) for i in range(len(original)))
    if coordinates != COORDINATE_COUNTS.get(name, 0): raise ContractError('game1182_coordinate_inventory')
    if name in ('AccountScreen', 'RoomsScreen'):
        items = tokens(source)
        calls = [(items[i].start, items[i + 2].end, 'init(minecraft, width, height)') for i in range(len(items) - 2)
                 if [t.value for t in items[i:i + 3]] == ['rebuildWidgets', '(', ')']]
        if len(calls) != (2 if name == 'AccountScreen' else 3): raise ContractError('game1182_rebuild_inventory')
        source = apply(source, calls)
    if name in ('DisplaySettingsScreen', 'LogoButton'):
        source = replace_once(source, 'import net.minecraft.client.gui.components.Tooltip;\n', '', 'game1182_tooltip_import')
        if name == 'LogoButton':
            source = replace_once(source, 'setTooltip(Tooltip.create(', GAME_UI + '.buttonTooltip(this, ', 'game1182_logo_tooltip')
            source = replace_once(source, '"P2WLAN · 虚拟局域网")));', '"P2WLAN · 虚拟局域网"));', 'game1182_logo_tooltip_end')
        else:
            source = replace_once(source, 'hud.setTooltip(Tooltip.create(', GAME_UI + '.buttonTooltip(hud, ', 'game1182_hud_tooltip')
            source = replace_once(source, '"只控制游戏内浮层；默认关闭。房间面板和延迟统计页始终可查看。F1 / F3 时不显示浮层。")));',
                                  '"只控制游戏内浮层；默认关闭。房间面板和延迟统计页始终可查看。F1 / F3 时不显示浮层。"));', 'game1182_hud_tooltip_end')
            source = replace_once(source, 'levels[level.ordinal()].setTooltip(Tooltip.create(', GAME_UI + '.buttonTooltip(levels[level.ordinal()], ', 'game1182_level_tooltip')
            source = replace_once(source, '(description)));', '(description));', 'game1182_level_tooltip_end')
    if any(t.value == 'GuiGraphics' for t in tokens(source)):
        if name not in GUI_FILES: raise ContractError('game1182_gui_inventory')
        source = replace_once(source, 'import net.minecraft.client.gui.GuiGraphics;',
                              'import com.mojang.blaze3d.vertex.PoseStack;', 'game1182_gui_import')
        source = apply(source, [(t.start, t.end, 'PoseStack') for t in tokens(source) if t.value == 'GuiGraphics'])
    items = tokens(source); edits = []; tooltip_count = hint_count = 0
    for i, token in enumerate(items):
        tail = [t.value for t in items[i:i + 4]]
        if len(tail) != 4: continue
        if name in GUI_FILES and token.value in ('draw', 'context') and tail[1] == '.' and tail[3] == '(':
            if tail[2] not in ('fill', 'drawString', 'drawCenteredString'):
                raise ContractError('game1182_gui_method')
            edits.append((token.start, items[i + 3].end, GAME_UI + '.' + tail[2] + '(' + token.value + ', '))
        elif tail[:3] == ['GameUi', '.', 'tooltip'] and tail[3] == '(':
            if name not in TOOLTIPS: raise ContractError('game1182_tooltip_inventory')
            end = close_paren(items, i + 3); cursor = i + 4; depth = 0; comma = None
            while cursor < end:
                value = items[cursor].value
                if value == '(': depth += 1
                elif value == ')': depth -= 1
                elif value == ',' and depth == 0:
                    if comma is not None: raise ContractError('game1182_tooltip_signature')
                    comma = cursor
                cursor += 1
            if comma is None: raise ContractError('game1182_tooltip_signature')
            edits.append((items[comma].start, items[comma].end, ', ' + TOOLTIPS[name] + ', mouseX, mouseY,'))
            tooltip_count += 1
        elif tail[1:] == ['.', 'setHint', '(']:
            if name not in HINTS or not token.identifier: raise ContractError('game1182_hint_inventory')
            edits.append((token.start, items[i + 3].end, GAME_UI + '.hint(' + token.value + ', ')); hint_count += 1
        elif tail[1] == '.' and tail[2] in ('getX', 'getY') and tail[3] == '(':
            if name not in COORDINATES or not token.identifier or i + 4 >= len(items) or items[i + 4].value != ')': raise ContractError('game1182_coordinate_inventory')
            edits.append((token.start, items[i + 4].end, token.value + '.' + tail[2][3].lower()))
        elif token.value in ('getX', 'getY') and tail[1:3] == ['(', ')']:
            if i > 0 and items[i - 1].value == '.': continue
            if name not in ('LogoButton', 'PixelButton'): raise ContractError('game1182_coordinate_inventory')
            edits.append((token.start, items[i + 2].end, 'this.' + token.value[3].lower()))
    if tooltip_count != (1 if name in TOOLTIPS else 0) or hint_count != HINTS.get(name, 0):
        raise ContractError('game1182_gui_call_inventory')
    source = apply(source, edits)
    if name in ('LogoButton', 'PixelButton'):
        source = replace_once(source, 'extends Button {', 'extends ' + GAME_UI + '.TooltipButton {', 'game1182_button_tooltip_owner')
        source = replace_once(source, 'DEFAULT_NARRATION', 'NO_TOOLTIP', 'game1182_button_constructor')
        source = replace_once(source, '@Override protected void renderWidget(', '@Override public void renderButton(', 'game1182_button_render')
        source = replace_once(source, 'isHovered() || isFocused()', 'isHoveredOrFocused()', 'game1182_button_hover')
    if name == 'PasswordField': source = replace_once(source, 'updateWidgetNarration(', 'updateNarration(', 'game1182_password_narration')
    if name == 'VirtualLanEntry':
        source = replace_once(source, '    @Override public Component getServerNarration() { return getNarration(); }\n', '', 'game1182_lan_narration')
    if name == 'JoinGameScreen':
        items = tokens(source)
        starts = [i for i in range(len(items) - 3) if [t.value for t in items[i:i + 4]] == ['Button', '.', 'builder', '(']]
        if len(starts) != 1: raise ContractError('game1182_button_builder_inventory')
        i = starts[0]; middle = close_paren(items, i + 3)
        if [t.value for t in items[middle + 1:middle + 4]] != ['.', 'bounds', '(']: raise ContractError('game1182_button_builder_signature')
        bounds = close_paren(items, middle + 3)
        if [t.value for t in items[bounds + 1:bounds + 5]] != ['.', 'build', '(', ')']: raise ContractError('game1182_button_builder_signature')
        arguments = source[items[i + 3].end:items[middle].start]
        rectangle = source[items[middle + 3].end:items[bounds].start]
        source = apply(source, [(items[i].start, items[bounds + 4].end, 'new Button(' + rectangle + ', ' + arguments + ')')])
    return source


def adapt(source, relative):
    relative = Path(relative); name = relative.stem
    if relative.as_posix() in EXCLUDED: raise ContractError('game1182_effect_owner')
    if relative.as_posix() not in INVENTORY: raise ContractError('game1182_source_inventory')
    if 'package io.p2wlan.mc.game.' not in source: raise ContractError('game1182_source_owner')
    source, counts = component_factories(source)
    if (counts['literal'], counts['translatable']) != COMPONENT_COUNTS.get(name, (0, 0)):
        raise ContractError('game1182_component_inventory')
    source = gui(source, name)
    if name == 'MultiplayerListMixin':
        source = replace_once(source,
            'protected MultiplayerListMixin(Minecraft client, int width, int height, int y, int itemHeight) { super(client, width, height, y, itemHeight); }',
            'protected MultiplayerListMixin(Minecraft client, int width, int height, int top, int bottom, int itemHeight) { super(client, width, height, top, bottom, itemHeight); }',
            'game1182_multiplayer_constructor')
    if name == 'ServerLoginMixin':
        source = replace_once(source, '"verifyLoginAndFinishConnectionSetup"', '"handleAcceptedLogin"', 'game1182_login_admission')
        source = replace_once(source, '"handleLoginAcknowledgement"', '"placeNewPlayer"', 'game1182_login_commit')
    if name == 'GameClientChecks':
        source = replace_once(source, 'multiplayer.getServers().add(ordinary, false);', 'multiplayer.getServers().add(ordinary);',
                              'game1182_fixture_server_list')
        source = replace_once(source,
            '                var reason = DisconnectedScreen.class.getDeclaredField("reason"); reason.setAccessible(true);\n'
            '                var text = (net.minecraft.network.chat.Component) reason.get(client.screen);',
            '                var text = GameFixtureApi.disconnectedReason(client.screen);',
            'game1182_fixture_disconnect_reason')
    return source, counts


def prepare(output):
    output = Path(output)
    if not output.is_absolute() or '..' in output.parts or not output.is_relative_to(PROJECT) or 'build' not in output.relative_to(PROJECT).parts:
        raise ValueError('GAME1182_OUTPUT_NOT_GENERATED')
    records = {}; pending = []
    for tree in TREES:
        root = SOURCE / tree / 'java'
        for path in sorted(root.rglob('*.java')):
            relative = path.relative_to(SOURCE)
            if relative.as_posix() in EXCLUDED: continue
            if path.is_symlink() or not stat.S_ISREG(path.stat().st_mode) or not 0 < path.stat().st_size <= MAX_SOURCE:
                raise ValueError('GAME1182_SOURCE_INVALID')
            raw = path.read_bytes(); text = raw.decode('utf-8')
            converted, counts = adapt(text, relative)
            target = output / relative
            if any(p.is_symlink() for p in (target, *target.parents) if p.is_relative_to(PROJECT)):
                raise ValueError('GAME1182_OUTPUT_LINK')
            pending.append((target, converted.encode('utf-8')))
            records[relative.as_posix()] = {'source_sha256': hashlib.sha256(raw).hexdigest(), 'component_factories': counts}
    if set(records) != INVENTORY: raise ContractError('game1182_source_inventory')
    if (output / 'source-bindings.json').is_symlink(): raise ValueError('GAME1182_OUTPUT_LINK')
    # Validate all source contracts before any generated file is published.
    for target, value in pending:
        target.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(prefix='game1182-', dir=target.parent)
        try:
            with os.fdopen(descriptor, 'wb') as stream: stream.write(value)
            os.replace(temporary, target)
        finally: Path(temporary).unlink(missing_ok=True)
    (output / 'source-bindings.json').write_text(json.dumps(records, sort_keys=True, indent=2) + '\n')
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try: prepare(args.output)
    except (OSError, ValueError, ContractError) as error: parser.exit(1, str(error) + '\n')
    print('PASS Minecraft 1.18.2 generated source contracts')


if __name__ == '__main__': main()
