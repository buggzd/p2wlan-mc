#!/usr/bin/env python3
"""Verify the pinned Forge 1.18.2 SDK input and its portable/source owners."""
import argparse
import io
import json
from pathlib import Path
import tomllib
import zipfile

from artifact_checks import MAX_BINARY, VerificationError, bind_sources, classfile, entries, metadata, read
from forge_gradle import bounded_file
from jarjar_artifact import portable
from legacy_gradle import field_names

SERVER_REFMAP = 'p2wlan-forge-1.18.2-server-refmap.json'
CLIENT_REFMAP = 'p2wlan-forge-1.18.2-client-refmap.json'
SERVER_CONFIG = 'p2wlan.forge.1.18.2.mixins.json'
CLIENT_CONFIG = 'p2wlan.forge.1.18.2.client.mixins.json'
ACTIVATION_MIXIN = 'io/p2wlan/mc/game/mixin/ClientConnectionMixin.class'
ACTIVATION_TOKENS = frozenset((b'activationHandler', b'channelActive(Lio/netty/channel/ChannelHandlerContext;)V',
    b'TAIL', b'm_178313_', b'CLIENTBOUND', b'net/minecraftforge/network/NetworkHooks', b'registerClientLoginChannel'))
SERVER_TARGETS = {
    'io/p2wlan/mc/game/server/mixin/ServerConnectionMixin': {
        'startTcpServerListener': 'Lnet/minecraft/server/network/ServerConnectionListener;m_9711_(Ljava/net/InetAddress;I)V'},
    'io/p2wlan/mc/game/server/mixin/ServerLoginConnectionMixin': {'channel': 'f_129468_:Lio/netty/channel/Channel;'},
    'io/p2wlan/mc/game/server/mixin/ServerLoginMixin': {
        'handleHello': 'Lnet/minecraft/server/network/ServerLoginPacketListenerImpl;m_5990_(Lnet/minecraft/network/protocol/login/ServerboundHelloPacket;)V',
        'handleAcceptedLogin': 'Lnet/minecraft/server/network/ServerLoginPacketListenerImpl;m_10055_()V',
        'placeNewPlayer': 'Lnet/minecraft/server/network/ServerLoginPacketListenerImpl;m_143699_(Lnet/minecraft/server/level/ServerPlayer;)V',
        'Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z': 'Lnet/minecraft/server/MinecraftServer;m_129797_()Z'},
}
CLIENT_TARGETS = {
    'io/p2wlan/mc/game/mixin/IntegratedServerMixin': {'isPublished': 'Lnet/minecraft/client/server/IntegratedServer;m_6992_()Z'},
    'io/p2wlan/mc/game/mixin/MultiplayerListMixin': {
        'refreshEntries': 'Lnet/minecraft/client/gui/screens/multiplayer/ServerSelectionList;m_99780_()V'},
    'io/p2wlan/mc/game/mixin/MultiplayerListAccessor': {'refreshEntries': 'm_99780_()V'},
    'io/p2wlan/mc/game/mixin/MultiplayerScreenMixin': {
        'tick': 'Lnet/minecraft/client/gui/screens/multiplayer/JoinMultiplayerScreen;m_96624_()V',
        'joinSelectedServer()V': 'Lnet/minecraft/client/gui/screens/multiplayer/JoinMultiplayerScreen;m_99729_()V'},
    'io/p2wlan/mc/game/mixin/ClientShutdownMixin': {'close': 'Lnet/minecraft/client/Minecraft;close()V'},
}
SHADOW_FIELDS = (
    ('mixin/MultiplayerListMixin', b'f_99766_', b'screen'),
    ('mixin/MultiplayerScreenMixin', b'f_99673_', b'serverSelectionList'),
    ('server/mixin/ServerLoginMixin', b'f_10013_', b'connection'),
)
GAME_TYPES = frozenset(('GameText', 'server/GameCommandApi', 'server/WorldCommands',
    'server/mixin/ServerConnectionMixin', 'server/mixin/ServerLoginConnectionMixin', 'server/mixin/ServerLoginMixin',
    'client/ClientDisplaySettings', 'client/ClientPlatform', 'client/ClientPlatform$Binding', 'client/ClientPlatform$IntegratedHooks',
    'client/GameUi', 'client/GameUi$TooltipButton', 'client/LatencyHud',
    'client/connect/GameJoins', 'client/connect/GameJoins$Attempt', 'client/connect/GameJoins$Handoff',
    'client/connect/GameJoins$Phase', 'client/connect/GameJoins$Registration', 'client/connect/GameJoins$Status',
    'client/connect/GameLogin', 'client/connect/MinecraftConnect', 'client/connect/MinecraftConnect$1',
    'client/lan/ClientNetworkView', 'client/lan/ClientNetworkView$Pending', 'client/lan/VanillaLanList',
    'client/lan/VanillaLanScreen', 'client/lan/VirtualLanEntry', 'client/screen/AccountScreen',
    'client/screen/AgentScreen', 'client/screen/AgentScreen$Action', 'client/screen/AgentScreen$Label',
    'client/screen/AgentScreen$ReplyAction', 'client/screen/AgentScreen$TaskAction', 'client/screen/DiagnosticsScreen',
    'client/screen/DisplaySettingsScreen', 'client/screen/DisplaySettingsScreen$1', 'client/screen/JoinGameScreen',
    'client/screen/LatencyStatsScreen', 'client/screen/LocalPanelScreen', 'client/screen/LogoButton',
    'client/screen/MenuLogo', 'client/screen/PasswordField', 'client/screen/PixelButton', 'client/screen/RoomFormScreen',
    'client/screen/RoomsScreen', 'client/screen/RuntimeScreen', 'mixin/ClientConnectionMixin', 'mixin/ClientShutdownMixin',
    'mixin/IntegratedServerMixin', 'mixin/MultiplayerListAccessor', 'mixin/MultiplayerListMixin', 'mixin/MultiplayerScreenMixin'))
FORGE_TYPES = frozenset(('ForgeP2wlan', 'ForgeP2wlan$Code', 'ForgeP2wlan$Factories', 'ForgeP2wlan$StartupError',
                        'ForgeRuntime', 'v1182/ForgeServerEvents', 'v1182/LocalNativeResources', 'v1182/ForgeClientEvents'))
CLASSES = frozenset('io/p2wlan/mc/game/' + name + '.class' for name in GAME_TYPES) \
    | frozenset('io/p2wlan/mc/forge/' + name + '.class' for name in FORGE_TYPES)
DEDICATED_CLASSES = frozenset('io/p2wlan/mc/forge/' + name + '.class' for name in FORGE_TYPES if name != 'v1182/ForgeClientEvents') \
    | frozenset('io/p2wlan/mc/game/' + name + '.class' for name in GAME_TYPES if name == 'GameText' or name.startswith('server/'))
MIXIN_CONFIG = {'required': True, 'minVersion': '0.8.5', 'package': 'io.p2wlan.mc.game.server.mixin',
                'compatibilityLevel': 'JAVA_17', 'refmap': SERVER_REFMAP,
                'mixins': ['ServerConnectionMixin', 'ServerLoginConnectionMixin', 'ServerLoginMixin'],
                'injectors': {'defaultRequire': 1}}
CLIENT_MIXIN_CONFIG = {'required': True, 'minVersion': '0.8.5', 'package': 'io.p2wlan.mc.game.mixin',
                'compatibilityLevel': 'JAVA_17', 'refmap': CLIENT_REFMAP,
                'client': ['ClientConnectionMixin', 'IntegratedServerMixin', 'MultiplayerListMixin',
                           'MultiplayerListAccessor', 'MultiplayerScreenMixin', 'ClientShutdownMixin'],
                'injectors': {'defaultRequire': 1}}


def same_json(value, expected):
    return json.dumps(value, sort_keys=True) == json.dumps(expected, sort_keys=True)


def refmap(value, expected):
    # Netty's activation selector and Forge's field retain their SDK names.
    if not same_json(value, {'mappings': expected, 'data': {'searge': expected}}):
        raise VerificationError('forge1182_refmap_contract')


def client_activation(value):
    """Require the selected Forge activation glue, not the Fabric marker."""
    if field_names(value) != {b'activationHandler'} or any(token not in value for token in ACTIVATION_TOKENS):
        raise VerificationError('forge1182_client_activation_contract')


def verify(path, development=False):
    with zipfile.ZipFile(path) as archive:
        names = entries(archive)
        try:
            descriptor = tomllib.loads(read(archive, 'META-INF/mods.toml', 65536).decode('utf-8'))
        except (ValueError, UnicodeError) as failure:
            raise VerificationError('forge1182_mod_contract') from failure
        mods = descriptor.get('mods')
        expected = [{'modId': 'forge', 'mandatory': True, 'versionRange': '[40.3.12]', 'ordering': 'NONE', 'side': 'BOTH'},
                    {'modId': 'minecraft', 'mandatory': True, 'versionRange': '[1.18.2]', 'ordering': 'NONE', 'side': 'BOTH'}]
        if descriptor.get('modLoader') != 'javafml' or descriptor.get('loaderVersion') != '[40,)' \
                or not same_json(descriptor.get('dependencies'), {'p2wlan': expected}) \
                or not isinstance(mods, list) or len(mods) != 1 or not isinstance(mods[0], dict) \
                or mods[0].get('modId') != 'p2wlan' or mods[0].get('version') != '0.1.0':
            raise VerificationError('forge1182_mod_contract')
        manifest = read(archive, 'META-INF/MANIFEST.MF', 8192).replace(b'\r\n ', b'').replace(b'\n ', b'')
        if manifest.splitlines().count(('MixinConfigs: ' + SERVER_CONFIG + ',' + CLIENT_CONFIG).encode()) != 1:
            raise VerificationError('forge1182_mixin_manifest')
        for name, expected in ((SERVER_CONFIG, MIXIN_CONFIG), (CLIENT_CONFIG, CLIENT_MIXIN_CONFIG)):
            if not same_json(metadata(archive, name), expected):
                raise VerificationError('forge1182_mixin_contract')
        refmap(metadata(archive, SERVER_REFMAP), SERVER_TARGETS)
        refmap(metadata(archive, CLIENT_REFMAP), CLIENT_TARGETS)
        if not same_json(metadata(archive, 'pack.mcmeta'), {'pack': {'description': 'P2WLAN', 'pack_format': 8}}):
            raise VerificationError('forge1182_pack_contract')
        for name in CLASSES:
            value = classfile(archive, name, 61)
            if name in DEDICATED_CLASSES and any(prefix in value for prefix in (b'net/minecraft/client/', b'net/minecraftforge/client/',
                                                                              b'io/p2wlan/mc/game/client/')):
                raise VerificationError('mod_dedicated_client_reference')
        if {name for name in names if name.endswith('.class')} != CLASSES:
            raise VerificationError('forge1182_class_inventory')
        if any(name.startswith(('com/sun/jna/', 'META-INF/versions/')) or '/fixture/' in name or name.endswith('Tests.class')
               or 'fixture' in name.lower() and name.endswith('.toml') for name in names):
            raise VerificationError('forge1182_class_owner')
        for owner, field, original in SHADOW_FIELDS:
            declared = field_names(classfile(archive, 'io/p2wlan/mc/game/' + owner + '.class', 61))
            if field not in declared or original in declared:
                raise VerificationError('forge1182_shadow_srg')
        client_activation(classfile(archive, ACTIVATION_MIXIN, 61))
        return portable(archive, '0.1.0', development, 'forge1182')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    check = sub.add_parser('verify')
    check.add_argument('archive', type=Path)
    check.add_argument('--development', action='store_true')
    args = parser.parse_args()
    try:
        natives = verify(io.BytesIO(bounded_file(args.archive, MAX_BINARY)), args.development)
        bind_sources(natives, args.development)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        parser.exit(1, (str(error) if isinstance(error, VerificationError) else 'FORGE1182_ARCHIVE_IO') + '\n')
    print('PASS Forge 1.18.2/40.3.12 input: Java 17, exact SRG Mixin bindings and portable/source owners')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
