"""Immutable exact Minecraft/loader facts for Universal build and packaging.

Profiles are exact pairs. SDK roles may be shared by installed loaders, while
the loader pin and Fabric API artifact identity remain specific to each pair.
This Python source is included in the Native input digest by native_bundle.
"""
from dataclasses import dataclass
import re
from types import MappingProxyType


class ProfileError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


@dataclass(frozen=True)
class LoaderPin:
    loader: str
    name: str
    version: str


@dataclass(frozen=True)
class FabricApiPin:
    version: str
    artifact_sha256: str
    mod_id: str = 'fabric-api'
    artifact_url: str | None = None


@dataclass(frozen=True)
class MixinSelection:
    server: tuple[str, ...]
    client: tuple[str, ...]
    server_refmap: str | None
    client_refmap: str | None


@dataclass(frozen=True)
class GameBinding:
    minecraft: str
    java_version: int
    family: str
    game_namespace: str
    mixins: MixinSelection
    server_config: str
    client_config: str

    @property
    def class_prefix(self):
        return self.game_namespace.replace('.', '/') + '/'


@dataclass(frozen=True)
class MinecraftProfile:
    binding: GameBinding
    loader_pin: LoaderPin
    sdk_role: str
    api: FabricApiPin | None = None

    @property
    def minecraft(self):
        return self.binding.minecraft

    @property
    def java_version(self):
        return self.binding.java_version

    @property
    def family(self):
        return self.binding.family

    @property
    def game_namespace(self):
        return self.binding.game_namespace

    @property
    def class_prefix(self):
        return self.binding.class_prefix

    @property
    def mixins(self):
        return self.binding.mixins

    @property
    def loader(self):
        return self.loader_pin.loader

    @property
    def name(self):
        return self.loader_pin.name

    @property
    def version(self):
        return self.loader_pin.version

    @property
    def api_version(self):
        return self.api.version if self.api else None

    @property
    def api_sha256(self):
        return self.api.artifact_sha256 if self.api else None


DEFAULT_MINECRAFT = '1.21.1'
_SERVER = ('ServerConnectionMixin', 'ServerLoginConnectionMixin', 'ServerLoginMixin')
_CLIENT = ('ClientConnectionMixin', 'IntegratedServerMixin', 'MultiplayerListMixin',
           'MultiplayerListAccessor', 'MultiplayerScreenMixin')
_FABRIC_LOADER = LoaderPin('fabric', 'Fabric', '0.16.14')
_FABRIC_API_1201 = FabricApiPin('0.92.12+1.20.1',
    '4197ff4fbdac13cffccd267c1bc59e9fbabb2b5683a9d5f8023f4b5ea16a1c1e')
_FABRIC_API_1211 = FabricApiPin('0.116.7+1.21.1',
    '08018cc48c97415a38016a00dbd5a2c7a460ba6b7a051690a8cb3dbb5e8482a4')
_FABRIC_1201 = GameBinding('1.20.1', 17, 'fabric', 'io.p2wlan.mc.fabric.game.v1_20_1',
    MixinSelection(_SERVER, _CLIENT + ('FabricLoginNetworkingMixin',),
                   'p2wlan-universal-fabric-1.20.1-server-refmap.json',
                   'p2wlan-universal-fabric-1.20.1-client-refmap.json'),
    'p2wlan.dispatch.fabric.1201.json', 'p2wlan.dispatch.fabric.1201.client.json')
_FML_1201 = GameBinding('1.20.1', 17, 'fml', 'io.p2wlan.mc.game.v1_20_1',
    MixinSelection(_SERVER, _CLIENT, 'p2wlan-universal-forge-1.20.1-server-refmap.json',
                   'p2wlan-universal-forge-1.20.1-client-refmap.json'),
    'p2wlan.dispatch.1201.json', 'p2wlan.dispatch.1201.client.json')
_FABRIC_1211 = GameBinding(DEFAULT_MINECRAFT, 21, 'fabric', 'io.p2wlan.mc.fabric.game',
    MixinSelection(_SERVER + ('ServerConfigurationMixin',), _CLIENT,
                   'p2wlan-universal-fabric-server-refmap.json',
                   'p2wlan-universal-fabric-client-refmap.json'),
    'p2wlan.dispatch.fabric.json', 'p2wlan.dispatch.fabric.client.json')
_FML_1211 = GameBinding(DEFAULT_MINECRAFT, 21, 'fml', 'io.p2wlan.mc.game',
    MixinSelection(_SERVER + ('ServerConfigurationMixin',), _CLIENT, None, None),
    'p2wlan.dispatch.json', 'p2wlan.dispatch.client.json')
_FABRIC_API_1182 = FabricApiPin('0.77.0+1.18.2',
    '6f822fb5aa481b4a6c1cfb8612bbfecc62a58e69d2c792f61a0eafa580e75999',
    artifact_url='https://cdn.modrinth.com/data/P7dR8mSH/versions/qk28POfr/fabric-api-0.77.0%2B1.18.2.jar')
_FORGE_1182 = LoaderPin('forge', 'Forge', '40.3.12')
_FABRIC_1182 = GameBinding('1.18.2', 17, 'fabric', 'io.p2wlan.mc.fabric.game.v1_18_2',
    MixinSelection(_SERVER, _CLIENT + ('FabricLoginNetworkingMixin',),
                   'p2wlan-universal-fabric-1.18.2-server-refmap.json',
                   'p2wlan-universal-fabric-1.18.2-client-refmap.json'),
    'p2wlan.dispatch.fabric.1182.json', 'p2wlan.dispatch.fabric.1182.client.json')
_FML_1182 = GameBinding('1.18.2', 17, 'fml', 'io.p2wlan.mc.game.v1_18_2',
    MixinSelection(_SERVER, _CLIENT + ('ClientShutdownMixin',),
                   'p2wlan-universal-forge-1.18.2-server-refmap.json',
                   'p2wlan-universal-forge-1.18.2-client-refmap.json'),
    'p2wlan.dispatch.1182.json', 'p2wlan.dispatch.1182.client.json')
_FABRIC_API_1165 = FabricApiPin('0.42.0+1.16',
    '3df8dd503f35aa0ac9fab8ad9f9a369fdfd0b1ab544af19a3d626d948fb4586c', 'fabric',
    'https://cdn.modrinth.com/data/P7dR8mSH/versions/0.42.0%2B1.16/fabric-api-0.42.0%2B1.16.jar')
_FORGE_1165 = LoaderPin('forge', 'Forge', '36.2.42')
_FABRIC_1165 = GameBinding('1.16.5', 17, 'fabric', 'io.p2wlan.mc.fabric.game.v1_16_5',
    MixinSelection(_SERVER + ('ConsoleSourceMixin',), _CLIENT + ('FabricLoginNetworkingMixin', 'LanNarrationMixin'),
                   'p2wlan-universal-fabric-1.16.5-server-refmap.json',
                   'p2wlan-universal-fabric-1.16.5-client-refmap.json'),
    'p2wlan.dispatch.fabric.1165.json', 'p2wlan.dispatch.fabric.1165.client.json')
_FML_1165 = GameBinding('1.16.5', 17, 'fml', 'io.p2wlan.mc.game.v1_16_5',
    MixinSelection(_SERVER + ('ConsoleSourceMixin',), _CLIENT + ('ClientShutdownMixin', 'LanNarrationMixin'),
                   'p2wlan-universal-forge-1.16.5-server-refmap.json',
                   'p2wlan-universal-forge-1.16.5-client-refmap.json'),
    'p2wlan.dispatch.1165.json', 'p2wlan.dispatch.1165.client.json')

# This is the publication allowlist, not a Cartesian product or an SDK-version
# heuristic. Early NeoForge uses the verified Forge 1.20.1 SDK/game binding.
_INSTALLATIONS = (
    MinecraftProfile(_FABRIC_1201, _FABRIC_LOADER, 'legacy_fabric', _FABRIC_API_1201),
    MinecraftProfile(_FML_1201, LoaderPin('neoforge', 'NeoForge', '47.1.106'), 'legacy_forge'),
    MinecraftProfile(_FML_1201, LoaderPin('forge', 'Forge', '47.3.2'), 'legacy_forge'),
    MinecraftProfile(_FABRIC_1211, _FABRIC_LOADER, 'fabric', _FABRIC_API_1211),
    MinecraftProfile(_FML_1211, LoaderPin('neoforge', 'NeoForge', '21.1.256'), 'neoforge'),
    MinecraftProfile(_FML_1211, LoaderPin('forge', 'Forge', '52.1.16'), 'forge'),
    MinecraftProfile(_FABRIC_1182, _FABRIC_LOADER, 'fabric_1182', _FABRIC_API_1182),
    MinecraftProfile(_FML_1182, _FORGE_1182, 'forge_1182'),
    MinecraftProfile(_FABRIC_1165, _FABRIC_LOADER, 'fabric_1165', _FABRIC_API_1165),
    MinecraftProfile(_FML_1165, _FORGE_1165, 'forge_1165'),
)
PROFILES = MappingProxyType({(item.minecraft, item.loader): item for item in _INSTALLATIONS})
MINECRAFT_VERSIONS = tuple(sorted({item.minecraft for item in _INSTALLATIONS},
                                key=lambda value: tuple(map(int, value.split('.')))))
LOADERS = tuple(dict.fromkeys(item.loader for item in _INSTALLATIONS))


def profile(minecraft=DEFAULT_MINECRAFT, loader='fabric'):
    if not isinstance(loader, str) or loader not in LOADERS:
        raise ProfileError('MINECRAFT_PROFILE_LOADER_UNSUPPORTED')
    if not isinstance(minecraft, str) or minecraft not in MINECRAFT_VERSIONS:
        raise ProfileError('MINECRAFT_PROFILE_VERSION_UNSUPPORTED')
    selected = PROFILES.get((minecraft, loader))
    if selected is None:
        raise ProfileError('MINECRAFT_PROFILE_PAIR_UNSUPPORTED')
    return selected


@dataclass(frozen=True)
class SDKInput:
    """One exact development SDK input, independent of publication admission."""
    role: str
    binding: GameBinding
    loader_pin: LoaderPin
    directory: str
    project: str
    selector: str | None
    wrapper_module: str | None
    output_directory: str
    cache_directory: str
    root_project: str | None = None
    api: FabricApiPin | None = None
    fixture_loader_range: str | None = None
    fixture_pack_format: int | None = None

    @property
    def minecraft(self):
        return self.binding.minecraft

    @property
    def loader(self):
        return self.loader_pin.loader

    def archive_name(self, mod_version):
        return f'p2wlan-{self.loader}-{self.minecraft}-{mod_version}.jar'


def _sdk(role, minecraft, loader, directory, project, selector, wrapper_module,
         output_directory, cache_directory, root_project=None,
         fixture_loader_range=None, fixture_pack_format=None):
    selected = profile(minecraft, loader)
    return SDKInput(role, selected.binding, selected.loader_pin, directory, project,
                    selector, wrapper_module, output_directory, cache_directory,
                    root_project, selected.api, fixture_loader_range, fixture_pack_format)


# SDK availability permits compilation and isolated fixture construction. Only
# PROFILES admits installed publication; extra SDKs never enter sdk_inputs().
_SDK_INPUTS = (
    _sdk('fabric', '1.21.1', 'fabric', 'java', 'fabric', '-PfabricAdapter', None,
         'fabric', 'standard-cache'),
    _sdk('neoforge', '1.21.1', 'neoforge', 'java', 'neoforge', '-PneoforgeAdapter', None,
         'neoforge', 'standard-cache'),
    _sdk('forge', '1.21.1', 'forge', 'java/forge', 'forge', None, 'forge_gradle',
         'forge', 'forge-cache'),
    _sdk('legacy_forge', '1.20.1', 'forge', 'java/forge-legacy', 'forge', None, 'legacy_gradle',
         'forge-legacy', 'legacy-forge-cache', 'p2wlan-minecraft-forge-legacy', '[47,48)', 15),
    _sdk('legacy_fabric', '1.20.1', 'fabric', 'java', 'fabric-legacy', '-PfabricLegacyAdapter', None,
         'fabric-legacy', 'standard-cache'),
    SDKInput('fabric_1182', _FABRIC_1182, _FABRIC_LOADER, 'java', 'fabric-1182',
             '-Pfabric1182Adapter', None, 'fabric-1182', 'standard-cache', api=_FABRIC_API_1182),
    SDKInput('forge_1182', _FML_1182, _FORGE_1182,
             'java/forge-1182', 'forge', None, 'forge_1182_gradle', 'forge-1182',
             'forge-1182-cache', 'p2wlan-minecraft-forge-1182',
             fixture_loader_range='[40,41)', fixture_pack_format=8),
    SDKInput('fabric_1165', _FABRIC_1165, _FABRIC_LOADER, 'java', 'fabric-1165',
             '-Pfabric1165Adapter', None, 'fabric-1165', 'standard-cache', api=_FABRIC_API_1165),
    SDKInput('forge_1165', _FML_1165, _FORGE_1165,
             'java/forge-1165', 'forge', None, 'forge_1165_gradle', 'forge-1165',
             'forge-1165-cache', 'p2wlan-minecraft-forge-1165',
             fixture_loader_range='[36,37)', fixture_pack_format=6),
)
SDK_INPUTS = MappingProxyType({item.role: item for item in _SDK_INPUTS})


def sdk_inputs():
    """Ordered build inputs required by the exact publication allowlist."""
    roles = {item.sdk_role for item in PROFILES.values()}
    if not roles <= SDK_INPUTS.keys():
        raise ProfileError('MINECRAFT_PROFILE_SDK_INPUT_MISSING')
    return MappingProxyType({role: item for role, item in SDK_INPUTS.items() if role in roles})


def sdk_input(minecraft=DEFAULT_MINECRAFT, loader='fabric'):
    selected = PROFILES.get((minecraft, loader)) if isinstance(minecraft, str) and isinstance(loader, str) else None
    if selected is not None:
        source = SDK_INPUTS.get(selected.sdk_role)
        if source is None:
            raise ProfileError('MINECRAFT_PROFILE_SDK_INPUT_MISSING')
        return source
    for source in SDK_INPUTS.values():
        if (source.minecraft, source.loader) == (minecraft, loader):
            return source
    raise ProfileError('MINECRAFT_PROFILE_SDK_PAIR_UNSUPPORTED')


def _numeric_version(value):
    if not isinstance(value, str) or re.fullmatch(r'[0-9]+(?:\.[0-9]+)*', value) is None:
        raise ProfileError('MINECRAFT_PROFILE_RANGE_VERSION_UNSUPPORTED')
    return tuple(map(int, value.split('.')))


def forge_profiles():
    """Exact installations using Forge's metadata, including early NeoForge."""
    selected = [item for item in PROFILES.values()
                if sdk_input(item.minecraft, item.loader).loader == 'forge']
    return tuple(sorted(selected, key=lambda item: (_numeric_version(item.version),
                                                   _numeric_version(item.minecraft), item.loader)))


def forge_range():
    # Maven's parser requires disjoint restrictions in ascending order.
    versions = sorted({item.version for item in forge_profiles()}, key=_numeric_version)
    return ','.join(f'[{value}]' for value in versions)


def fml_range():
    majors = sorted({_numeric_version(item.version)[0] for item in forge_profiles()})
    return ','.join(f'[{value},{value + 1})' for value in majors)


def game_range():
    versions = sorted({item.minecraft for item in forge_profiles()}, key=_numeric_version)
    return ','.join(f'[{value}]' for value in versions)
