#!/usr/bin/env python3
"""Verify the pinned Forge 1.16.5 raw SDK leaf; installed ownership is checked by the final Universal archive."""
import argparse
import io
import json
from pathlib import Path
import stat
import tomllib
import zipfile

from artifact_checks import MAX_BINARY, VerificationError, classfile, entries, metadata, read
from forge_gradle import bounded_file
from legacy_gradle import field_names

SERVER_REFMAP = 'p2wlan-forge-1.16.5-server-refmap.json'
CLIENT_REFMAP = 'p2wlan-forge-1.16.5-client-refmap.json'
SERVER_CONFIG = 'p2wlan.forge.1.16.5.mixins.json'
CLIENT_CONFIG = 'p2wlan.forge.1.16.5.client.mixins.json'
ACTIVATION_MIXIN = 'io/p2wlan/mc/game/mixin/ClientConnectionMixin.class'
ACTIVATION_TOKENS = frozenset((b'activationHandler', b'channelActive(Lio/netty/channel/ChannelHandlerContext;)V',
    b'TAIL', b'getDirection', b'CLIENTBOUND', b'net/minecraftforge/fml/network/NetworkHooks', b'registerClientLoginChannel'))
SERVER_TARGETS = {
    'io/p2wlan/mc/game/server/mixin/ServerConnectionMixin': {
        'startTcpServerListener': 'Lnet/minecraft/network/NetworkSystem;func_151265_a(Ljava/net/InetAddress;I)V'},
    'io/p2wlan/mc/game/server/mixin/ServerLoginConnectionMixin': {'channel': 'field_150746_k:Lio/netty/channel/Channel;'},
    'io/p2wlan/mc/game/server/mixin/ServerLoginMixin': {
        'handleHello': 'Lnet/minecraft/network/login/ServerLoginNetHandler;func_147316_a(Lnet/minecraft/network/login/client/CLoginStartPacket;)V',
        'handleAcceptedLogin': 'Lnet/minecraft/network/login/ServerLoginNetHandler;func_147326_c()V',
        'tick': 'Lnet/minecraft/network/login/ServerLoginNetHandler;func_73660_a()V',
        'Lnet/minecraft/server/management/PlayerList;placeNewPlayer(Lnet/minecraft/network/NetworkManager;Lnet/minecraft/entity/player/ServerPlayerEntity;)V':
            'Lnet/minecraft/server/management/PlayerList;func_72355_a(Lnet/minecraft/network/NetworkManager;Lnet/minecraft/entity/player/ServerPlayerEntity;)V',
        'Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z': 'Lnet/minecraft/server/MinecraftServer;func_71266_T()Z'},
}
CLIENT_TARGETS = {
    'io/p2wlan/mc/game/mixin/IntegratedServerMixin': {'isPublished': 'Lnet/minecraft/server/integrated/IntegratedServer;func_71344_c()Z'},
    'io/p2wlan/mc/game/mixin/MultiplayerListMixin': {
        'refreshEntries': 'Lnet/minecraft/client/gui/screen/ServerSelectionList;func_195094_h()V'},
    'io/p2wlan/mc/game/mixin/MultiplayerListAccessor': {'refreshEntries': 'func_195094_h()V'},
    'io/p2wlan/mc/game/mixin/MultiplayerScreenMixin': {
        'tick': 'Lnet/minecraft/client/gui/screen/MultiplayerScreen;func_231023_e_()V',
        'joinSelectedServer()V': 'Lnet/minecraft/client/gui/screen/MultiplayerScreen;func_146796_h()V'},
    'io/p2wlan/mc/game/mixin/LanNarrationMixin': {
        'setSelected(Lnet/minecraft/client/gui/screen/ServerSelectionList$Entry;)V':
            'Lnet/minecraft/client/gui/screen/ServerSelectionList;func_241215_a_(Lnet/minecraft/client/gui/screen/ServerSelectionList$Entry;)V'},
    'io/p2wlan/mc/game/mixin/ClientShutdownMixin': {'close': 'Lnet/minecraft/client/Minecraft;close()V'},
}
SHADOW_FIELDS = (
    ('mixin/MultiplayerListMixin', b'field_148200_k', b'screen'),
    ('mixin/MultiplayerScreenMixin', b'field_146803_h', b'serverSelectionList'),
    ('server/mixin/ServerLoginMixin', b'field_147333_a', b'connection'),
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
    'client/screen/RoomsScreen', 'client/screen/RuntimeScreen', 'mixin/ClientConnectionMixin', 'mixin/ClientShutdownMixin', 'mixin/LanNarrationMixin',
    'mixin/IntegratedServerMixin', 'mixin/MultiplayerListAccessor', 'mixin/MultiplayerListMixin', 'mixin/MultiplayerScreenMixin'))
FORGE_TYPES = frozenset(('ForgeP2wlan', 'ForgeP2wlan$Code', 'ForgeP2wlan$Factories', 'ForgeP2wlan$StartupError',
                        'ForgeRuntime', 'v1165/ForgeServerEvents', 'v1165/LocalNativeResources', 'v1165/ForgeClientEvents'))
CLASSES = frozenset('io/p2wlan/mc/game/' + name + '.class' for name in GAME_TYPES) \
    | frozenset('io/p2wlan/mc/forge/' + name + '.class' for name in FORGE_TYPES)
DEDICATED_CLASSES = frozenset('io/p2wlan/mc/forge/' + name + '.class' for name in FORGE_TYPES if name != 'v1165/ForgeClientEvents') \
    | frozenset('io/p2wlan/mc/game/' + name + '.class' for name in GAME_TYPES if name == 'GameText' or name.startswith('server/'))
RESOURCE_LIMITS = {'META-INF/MANIFEST.MF': 8192, 'META-INF/mods.toml': 65536, 'pack.mcmeta': 65536,
                   SERVER_CONFIG: 65536, CLIENT_CONFIG: 65536, SERVER_REFMAP: 65536, CLIENT_REFMAP: 65536}
SDK_FILES = CLASSES | frozenset(RESOURCE_LIMITS)
SDK_DIRECTORIES = frozenset(name[:offset + 1] for name in SDK_FILES for offset, value in enumerate(name) if value == '/')
MAX_SDK_TOTAL = 32 * 1024 * 1024
MIXIN_CONFIG = {'required': True, 'minVersion': '0.8.4', 'package': 'io.p2wlan.mc.game.server.mixin',
                'compatibilityLevel': 'JAVA_17', 'refmap': SERVER_REFMAP,
                'mixins': ['ServerConnectionMixin', 'ServerLoginConnectionMixin', 'ServerLoginMixin'],
                'injectors': {'defaultRequire': 1}}
CLIENT_MIXIN_CONFIG = {'required': True, 'minVersion': '0.8.4', 'package': 'io.p2wlan.mc.game.mixin',
                'compatibilityLevel': 'JAVA_17', 'refmap': CLIENT_REFMAP,
                'client': ['ClientConnectionMixin', 'IntegratedServerMixin', 'MultiplayerListMixin',
                           'MultiplayerListAccessor', 'MultiplayerScreenMixin', 'LanNarrationMixin', 'ClientShutdownMixin'],
                'injectors': {'defaultRequire': 1}}


def same_json(value, expected):
    return json.dumps(value, sort_keys=True) == json.dumps(expected, sort_keys=True)


def refmap(value, expected):
    # Netty's activation selector and Forge's field retain their SDK names.
    if not same_json(value, {'mappings': expected, 'data': {'searge': expected}}):
        raise VerificationError('forge1165_refmap_contract')


def client_activation(value):
    """Require the selected Forge activation glue, not the Fabric marker."""
    if field_names(value) != {b'activationHandler'} or any(token not in value for token in ACTIVATION_TOKENS):
        raise VerificationError('forge1165_client_activation_contract')


def member_inventory(archive, names):
    """The raw SDK leaf carries only its classes and seven declared resources."""
    if any(name.endswith('.jar') or name.startswith('p2wlan/native/') for name in names):
        raise VerificationError('forge1165_raw_sdk_nested_payload')
    if {name for name in names if name.endswith('.class')} != CLASSES:
        raise VerificationError('forge1165_class_inventory')
    if {item.filename for item in archive.infolist() if not item.is_dir()} != SDK_FILES:
        raise VerificationError('forge1165_resource_inventory')
    total = 0
    for item in archive.infolist():
        directory = item.is_dir()
        kind = stat.S_IFMT(item.external_attr >> 16)
        if item.flag_bits & 0x41 or item.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED) \
                or kind not in (0, stat.S_IFDIR if directory else stat.S_IFREG) \
                or not directory and item.external_attr & 0x10 \
                or directory and (item.filename not in SDK_DIRECTORIES or item.file_size != 0):
            raise VerificationError('forge1165_archive_entry_invalid')
        if not directory:
            maximum = RESOURCE_LIMITS.get(item.filename, 1024 * 1024)
            total += item.file_size
            if not 0 < item.file_size <= maximum or total > MAX_SDK_TOTAL:
                raise VerificationError('forge1165_archive_member_capacity')


def verify(path, development=False):
    with zipfile.ZipFile(path) as archive:
        names = entries(archive)
        member_inventory(archive, names)
        try:
            descriptor = tomllib.loads(read(archive, 'META-INF/mods.toml', 65536).decode('utf-8'))
        except (ValueError, UnicodeError) as failure:
            raise VerificationError('forge1165_mod_contract') from failure
        mods = descriptor.get('mods')
        expected = [{'modId': 'forge', 'mandatory': True, 'versionRange': '[36.2.42]', 'ordering': 'NONE', 'side': 'BOTH'},
                    {'modId': 'minecraft', 'mandatory': True, 'versionRange': '[1.16.5]', 'ordering': 'NONE', 'side': 'BOTH'}]
        if descriptor.get('modLoader') != 'javafml' or descriptor.get('loaderVersion') != '[36,)' \
                or not same_json(descriptor.get('dependencies'), {'p2wlan': expected}) \
                or not isinstance(mods, list) or len(mods) != 1 or not isinstance(mods[0], dict) \
                or mods[0].get('modId') != 'p2wlan' or mods[0].get('version') != '0.1.0':
            raise VerificationError('forge1165_mod_contract')
        manifest = read(archive, 'META-INF/MANIFEST.MF', 8192).replace(b'\r\n ', b'').replace(b'\n ', b'')
        if manifest.splitlines().count(('MixinConfigs: ' + SERVER_CONFIG + ',' + CLIENT_CONFIG).encode()) != 1:
            raise VerificationError('forge1165_mixin_manifest')
        for name, expected in ((SERVER_CONFIG, MIXIN_CONFIG), (CLIENT_CONFIG, CLIENT_MIXIN_CONFIG)):
            if not same_json(metadata(archive, name), expected):
                raise VerificationError('forge1165_mixin_contract')
        refmap(metadata(archive, SERVER_REFMAP), SERVER_TARGETS)
        refmap(metadata(archive, CLIENT_REFMAP), CLIENT_TARGETS)
        if not same_json(metadata(archive, 'pack.mcmeta'), {'pack': {'description': 'P2WLAN', 'pack_format': 6}}):
            raise VerificationError('forge1165_pack_contract')
        for name in CLASSES:
            value = classfile(archive, name, 61)
            if name in DEDICATED_CLASSES and any(prefix in value for prefix in (b'net/minecraft/client/', b'net/minecraftforge/client/',
                                                                              b'io/p2wlan/mc/game/client/')):
                raise VerificationError('mod_dedicated_client_reference')
        if any(name.startswith(('com/sun/jna/', 'META-INF/versions/')) or '/fixture/' in name or name.endswith('Tests.class')
               or 'fixture' in name.lower() and name.endswith('.toml') for name in names):
            raise VerificationError('forge1165_class_owner')
        for owner, field, original in SHADOW_FIELDS:
            declared = field_names(classfile(archive, 'io/p2wlan/mc/game/' + owner + '.class', 61))
            if field not in declared or original in declared:
                raise VerificationError('forge1165_shadow_srg')
        client_activation(classfile(archive, ACTIVATION_MIXIN, 61))
        return {'schema': 1, 'minecraft': '1.16.5', 'forge': '36.2.42', 'java': 17,
                'shape': 'raw-sdk-leaf', 'classes': len(CLASSES), 'game_classes': len(GAME_TYPES),
                'verification': 'sdk-class/refmap-contract'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    check = sub.add_parser('verify')
    check.add_argument('archive', type=Path)
    check.add_argument('--development', action='store_true')
    args = parser.parse_args()
    try:
        result = verify(io.BytesIO(bounded_file(args.archive, MAX_BINARY)), args.development)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        parser.exit(1, (str(error) if isinstance(error, VerificationError) else 'FORGE1165_ARCHIVE_IO') + '\n')
    print('PASS Forge 1.16.5/36.2.42 raw SDK leaf: Java 17, exact SRG Mixin bindings and activation glue')
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
