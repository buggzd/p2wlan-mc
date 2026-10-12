"""Loader-independent JarJar bytecode, module and native/source contracts."""
import io
import zipfile
from artifact_checks import (MAX_BINARY, PLATFORMS, VerificationError, classfile,
                             entries, executable_name, metadata, native_manifest, read)
from verify_mod_jar import COMMON, TRANSPORT

WORLD_COMMON = tuple('io/p2wlan/mc/common/' + name + '.class' for name in
                     ('WorldSessions', 'WorldSessions$World', 'WorldHost', 'WorldHost$Target', 'WorldHost$TargetFactory', 'LayerNativeResources'))
CLASS_PACKAGES = {'common': 'io/p2wlan/mc/common/', 'netty-transport': 'io/p2wlan/mc/transport/'}


def _runtime_name(name):
    # Multi-release entries can otherwise hide a second package owner behind
    # META-INF while remaining visible to the Java runtime.
    if name.startswith('META-INF/versions/'):
        name = name.split('/', 3)[-1]
    return name


def portable(archive, version, development, loader="neoforge"):
    archive_names = entries(archive)
    if any(name.endswith('.class') and _runtime_name(name).startswith(tuple(CLASS_PACKAGES.values())) for name in archive_names):
        raise VerificationError(f"{loader}_jarjar_class_owner")
    if any(_runtime_name(name).startswith('p2wlan/native/') and not name.endswith('/') for name in archive_names):
        raise VerificationError(f"{loader}_native_resource_owner")
    jars = metadata(archive, 'META-INF/jarjar/metadata.json')
    jars = jars.get('jars') if isinstance(jars, dict) else None
    if not isinstance(jars, list) or len(jars) != 3:
        raise VerificationError(f"{loader}_nested_resources_missing")
    seen = set()
    common_count = transport_count = 0
    natives = []
    identities = set()
    for item in jars:
        if not isinstance(item, dict) or set(item) != {'identifier', 'version', 'path', 'isObfuscated'}:
            raise VerificationError(f"{loader}_jarjar_metadata")
        identifier = item['identifier']
        if not isinstance(identifier, dict) or set(identifier) != {'group', 'artifact'} \
                or identifier['group'] != 'io.p2wlan.mc' or identifier['artifact'] not in {'common', 'netty-transport', 'native-bundle'} \
                or identifier['artifact'] in identities or item['isObfuscated'] is not False \
                or item['version'] != {'range': '[0.1.0,)', 'artifactVersion': '0.1.0'}:
            raise VerificationError(f"{loader}_jarjar_metadata")
        identities.add(identifier['artifact'])
        path = item.get('path') if isinstance(item, dict) else None
        if not isinstance(path, str) or not path.startswith('META-INF/jarjar/') or path in seen:
            raise VerificationError(f"{loader}_nested_resource_invalid")
        seen.add(path)
        with zipfile.ZipFile(io.BytesIO(read(archive, path, MAX_BINARY))) as child:
            names = entries(child)
            artifact = identifier['artifact']
            if 'com/sun/jna/Native.class' in names:
                raise VerificationError(f"{loader}_duplicate_bootstrap_jna")
            classes = [name for name in names if name.endswith('.class')]
            if artifact == 'native-bundle' and classes:
                raise VerificationError(f"{loader}_native_module_classes")
            if artifact in CLASS_PACKAGES and any(not _runtime_name(name).startswith(CLASS_PACKAGES[artifact]) for name in classes):
                raise VerificationError(f"{loader}_jarjar_class_owner")
            if artifact != 'native-bundle' and any(_runtime_name(name).startswith('p2wlan/native/') and not name.endswith('/') for name in names):
                raise VerificationError(f"{loader}_native_resource_owner")
            if identifier['artifact'] == 'native-bundle':
                raw_manifest = read(child, 'META-INF/MANIFEST.MF', 4096)
                pairs = [line.split(b': ', 1) for line in raw_manifest.splitlines() if line]
                if any(len(pair) != 2 for pair in pairs) or len(pairs) != 2 or dict(pairs) != {
                        b'Manifest-Version': b'1.0', b'Automatic-Module-Name': b'io.p2wlan.mc.nativebundle'}:
                    raise VerificationError(f"{loader}_native_module_identity")
            if (identifier['artifact'] == 'common') != (COMMON in names) \
                    or (identifier['artifact'] == 'netty-transport') != (TRANSPORT[0] in names):
                raise VerificationError(f"{loader}_jarjar_component_identity")
            if COMMON in names:
                common_count += 1
                for name in WORLD_COMMON:
                    classfile(child, name, 61)
            if TRANSPORT[0] in names:
                transport_count += 1
                for name in TRANSPORT:
                    classfile(child, name, 61)
            if COMMON in names or TRANSPORT[0] in names:
                for name in names:
                    if name.endswith('.class'):
                        value = classfile(child, name, 61)
                        if any(prefix in value for prefix in (b'net/minecraft/', b'net/fabricmc/', b'net/neoforged/', b'net/minecraftforge/')):
                            raise VerificationError('mod_common_game_dependency')
            manifests = sorted(name for name in names if name.startswith('p2wlan/native/') and name.endswith('/manifest.properties'))
            resources = [native_manifest(child, name, version, development) for name in manifests]
            admitted = set(manifests) | {f"p2wlan/native/{item['verification-platform']}/{executable_name(item['verification-platform'])}" for item in resources}
            if any(_runtime_name(name).startswith('p2wlan/native/') and not name.endswith('/') and name not in admitted for name in names):
                raise VerificationError('mod_native_resource_unexpected')
            natives.extend(resources)
    if seen != {name for name in archive_names if name.startswith('META-INF/jarjar/') and name.endswith('.jar')}:
        raise VerificationError(f"{loader}_unlisted_nested_resource")
    if (common_count, transport_count) != (1, 1):
        raise VerificationError(f"{loader}_portable_resources_ambiguous")
    platforms = [item['verification-platform'] for item in natives]
    if not natives or len(platforms) > len(PLATFORMS) or len(set(platforms)) != len(platforms):
        raise VerificationError('mod_native_resources_ambiguous')
    return natives
