#!/usr/bin/env python3
"""Generate exact Minecraft 1.16.5 APIs from the unique shared game source."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import tempfile

import game_sources_1182 as shared
from source_contract import ContractError, replace_once

PROJECT = shared.PROJECT
SOURCE = shared.SOURCE
INVENTORY = shared.INVENTORY
EXCLUDED = shared.EXCLUDED
WIDGET_COUNTS = {'AgentScreen': 6, 'JoinGameScreen': 1, 'LocalPanelScreen': 1}
SCREENSHOTS = ('network', 'display-basic', 'display-advanced', 'latency-statistics', 'multiplayer', 'hud-advanced', 'pause-logo')
PLAYER_COMMIT = 'Lnet/minecraft/server/players/PlayerList;placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;)V'


def adapt(source, relative):
    relative = Path(relative); name = relative.stem
    source, counts = shared.adapt(source, relative)
    items = shared.tokens(source)
    widgets = [(items[i].start, items[i].end, 'addButton') for i in range(len(items) - 1)
               if items[i].value == 'addRenderableWidget' and items[i + 1].value == '(']
    if len(widgets) != WIDGET_COUNTS.get(name, 0): raise ContractError('game1165_widget_inventory')
    source = shared.apply(source, widgets)
    if name in ('LogoButton', 'PixelButton'):
        source = replace_once(source, 'isHoveredOrFocused()', '(isHovered() || isFocused())', 'game1165_button_hover')
    if name == 'PasswordField':
        for imported in ('NarratedElementType', 'NarrationElementOutput'):
            source = replace_once(source, 'import net.minecraft.client.gui.narration.' + imported + ';\n', '', 'game1165_password_import')
        source = replace_once(source,
            '    @Override public void updateNarration(NarrationElementOutput builder) {\n'
            '        builder.add(NarratedElementType.TITLE, getMessage());\n    }',
            '    @Override protected net.minecraft.network.chat.MutableComponent createNarrationMessage() {\n'
            '        return getMessage().copy();\n    }', 'game1165_password_label')
    if name == 'VirtualLanEntry':
        source = replace_once(source, '@Override public Component getNarration()', 'public Component getNarration()', 'game1165_lan_narration')
    if name == 'ServerLoginMixin':
        source = replace_once(source,
            '@Inject(method = {"handleHello", "handleAcceptedLogin", "placeNewPlayer"},\n'
            '            at = @At("HEAD"), cancellable = true, require = 1)',
            '@Inject(method = {"handleHello", "handleAcceptedLogin"},\n'
            '            at = @At("HEAD"), cancellable = true, require = 2, allow = 2)', 'game1165_login_heads')
        source = replace_once(source, '    }\n}\n',
            '    }\n\n'
            '    // This SDK commits players directly from both the immediate\n'
            '    // and delayed tick paths. Reuse the exact original-owner check\n'
            '    // immediately before either PlayerList placement effect.\n'
            '    @Inject(method = {"tick", "handleAcceptedLogin"}, at = @At(value = "INVOKE",\n'
            '            target = "' + PLAYER_COMMIT + '"),\n'
            '            cancellable = true, require = 2, allow = 2)\n'
            '    private void p2wlan$playerCommit(CallbackInfo result) { p2wlan$currentWorld(result); }\n}\n',
            'game1165_login_commit')
    if name in ('GameFixtureData', 'GameClientChecks'):
        source = replace_once(source, 'JsonParser.parseString(', 'new JsonParser().parse(', 'game1165_fixture_json_parser')
        receiver = 'value' if name == 'GameFixtureData' else 'ready'
        source = replace_once(source, receiver + '.keySet().equals(', 'io.p2wlan.mc.common.JsonTrees.fields(' + receiver + ', ',
                              'game1165_fixture_json_fields')
    if name == 'GameClientChecks':
        source = replace_once(source,
            '((net.minecraft.client.gui.screens.multiplayer.ServerSelectionList.Entry) entry).getNarration()',
            'entry.getNarration()', 'game1165_fixture_lan_label')
        for screenshot in SCREENSHOTS:
            prefix = 'net.minecraft.client.Screenshot.grab(data.root.toFile(),"p2wlan-' + screenshot + '.png",'
            source = replace_once(source, prefix + 'client.getMainRenderTarget(),message->{});',
                prefix + 'client.getWindow().getWidth(),client.getWindow().getHeight(),client.getMainRenderTarget(),message->{});',
                'game1165_fixture_screenshot')
    return source, counts


def prepare(output):
    output = Path(output)
    if not output.is_absolute() or '..' in output.parts or not output.is_relative_to(PROJECT) \
            or 'build' not in output.relative_to(PROJECT).parts:
        raise ValueError('GAME1165_OUTPUT_NOT_GENERATED')
    pending = []; records = {}
    for tree in shared.TREES:
        for path in sorted((SOURCE / tree / 'java').rglob('*.java')):
            relative = path.relative_to(SOURCE)
            if relative.as_posix() in EXCLUDED: continue
            if path.is_symlink() or not stat.S_ISREG(path.stat().st_mode) or not 0 < path.stat().st_size <= shared.MAX_SOURCE:
                raise ValueError('GAME1165_SOURCE_INVALID')
            raw = path.read_bytes(); converted, counts = adapt(raw.decode('utf-8'), relative)
            target = output / relative
            if any(p.is_symlink() for p in (target, *target.parents) if p.is_relative_to(PROJECT)):
                raise ValueError('GAME1165_OUTPUT_LINK')
            pending.append((target, converted.encode('utf-8')))
            records[relative.as_posix()] = {'source_sha256': hashlib.sha256(raw).hexdigest(), 'component_factories': counts}
    if set(records) != INVENTORY: raise ContractError('game1165_source_inventory')
    if (output / 'source-bindings.json').is_symlink(): raise ValueError('GAME1165_OUTPUT_LINK')
    for target, content in pending:
        target.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(prefix='game1165-', dir=target.parent)
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
    print('PASS Minecraft 1.16.5 generated source contracts')


if __name__ == '__main__': main()
