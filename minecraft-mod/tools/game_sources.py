#!/usr/bin/env python3
"""Generate narrow Minecraft 1.20.1 adaptations from the shared game source."""
import argparse
import os
from pathlib import Path
import tempfile

from source_contract import ContractError, replace_once

PROJECT = Path(__file__).resolve().parents[1]
RELATIVE = Path('io/p2wlan/mc/game/mixin/MultiplayerListMixin.java')
BEFORE = 'protected MultiplayerListMixin(Minecraft client, int width, int height, int y, int itemHeight) { super(client, width, height, y, itemHeight); }'
AFTER = 'protected MultiplayerListMixin(Minecraft client, int width, int height, int top, int bottom, int itemHeight) { super(client, width, height, top, bottom, itemHeight); }'
LOGIN_RELATIVE = Path('io/p2wlan/mc/game/server/mixin/ServerLoginMixin.java')
LOGIN_BEFORE = '"verifyLoginAndFinishConnectionSetup"'
LOGIN_AFTER = '"handleAcceptedLogin"'
LOGIN_ACK_BEFORE = '"handleLoginAcknowledgement"'
LOGIN_ACK_AFTER = '"placeNewPlayer"'


def adapt(source):
    for anchor in ('package io.p2wlan.mc.game.mixin;',
                   'public abstract class MultiplayerListMixin extends ObjectSelectionList<ServerSelectionList.Entry> {'):
        if source.count(anchor) != 1:
            raise ContractError('upstream_contract_changed:legacy_multiplayer_list_owner')
    return replace_once(source, BEFORE, AFTER, 'legacy_multiplayer_list_constructor')


def adapt_login(source):
    for anchor in ('package io.p2wlan.mc.game.server.mixin;',
                   '@Mixin(ServerLoginPacketListenerImpl.class)',
                   'public abstract class ServerLoginMixin {'):
        if source.count(anchor) != 1:
            raise ContractError('upstream_contract_changed:legacy_login_owner')
    value = replace_once(source, LOGIN_BEFORE, LOGIN_AFTER, 'legacy_login_profile_admission')
    return replace_once(value, LOGIN_ACK_BEFORE, LOGIN_ACK_AFTER, 'legacy_login_player_commit')


def prepare(output):
    output = Path(output)
    if not output.is_absolute() or '..' in output.parts or not output.is_relative_to(PROJECT) \
            or 'build' not in output.relative_to(PROJECT).parts:
        raise ValueError('LEGACY_GAME_OUTPUT_NOT_GENERATED')
    values = []
    for relative, tree, adapter in ((RELATIVE, 'client', adapt), (LOGIN_RELATIVE, 'server', adapt_login)):
        target = output / relative
        if any(path.is_symlink() for path in (target, *target.parents) if path.is_relative_to(PROJECT)):
            raise ValueError('LEGACY_GAME_OUTPUT_LINK')
        source = PROJECT / f'java/game-1.21.1/src/{tree}/java' / relative
        if source.is_symlink() or not source.is_file() or source.stat().st_size > 65536:
            raise ValueError('LEGACY_GAME_SOURCE_INVALID')
        values.append((target, adapter(source.read_text(encoding='utf-8')).encode('utf-8')))
    for target, value in values:
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.is_symlink():
            raise ValueError('LEGACY_GAME_OUTPUT_LINK')
        descriptor, temporary = tempfile.mkstemp(prefix='game-source-', dir=target.parent)
        try:
            with os.fdopen(descriptor, 'wb') as stream:
                stream.write(value)
            os.replace(temporary, target)
        finally:
            Path(temporary).unlink(missing_ok=True)
    return output / RELATIVE


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    try:
        prepare(args.output)
    except (OSError, ValueError, ContractError) as error:
        parser.exit(1, str(error) + '\n')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
