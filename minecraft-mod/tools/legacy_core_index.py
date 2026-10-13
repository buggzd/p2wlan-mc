"""Bind the Forge36 early locator to the canonical nested Universal owners."""
import hashlib
import io
import re
import zipfile

from artifact_checks import VerificationError, entries, read
from universal_jna import isolated_resources

INDEX = 'META-INF/p2wlan/legacy-core.properties'
SERVICE = 'META-INF/services/net.minecraftforge.forgespi.locating.IModLocator'
SERVICE_CLASS = 'io.p2wlan.mc.legacybootstrap.LegacyModLocator'
OWNERS = {'common': 'META-INF/jarjar/common.jar', 'transport': 'META-INF/jarjar/transport.jar',
          'native': 'META-INF/jarjar/native.jar', 'jna': 'META-INF/jars/jna.jar'}
PLATFORMS = ('linux-aarch64', 'linux-x86_64', 'macos-aarch64', 'macos-x86_64', 'windows-x86_64')
MAX_OWNER = 128 * 1024 * 1024
MAX_INDEX = 8192


def resource_names():
    native = {f'p2wlan/native/{platform}/{name}' for platform in PLATFORMS
              for name in ('manifest.properties', 'p2wlan-mc-agent.exe' if platform.startswith('windows-') else 'p2wlan-mc-agent')}
    return frozenset(native | set(isolated_resources()))


def index_bytes(files, source_revision, native_source_sha256):
    if re.fullmatch('[0-9a-f]{40}', source_revision or '') is None \
            or re.fullmatch('[0-9a-f]{64}', native_source_sha256 or '') is None:
        raise VerificationError('legacy_core_source_identity')
    records = {'schema': '1', 'minecraft': '1.16.5', 'forge': '36.2.42', 'modlauncher': '8.1.3',
               'java': '17', 'mod-version': '0.1.0', 'source-revision': source_revision,
               'native-source-sha256': native_source_sha256}
    for owner, path in OWNERS.items():
        value = files.get(path)
        if not isinstance(value, bytes) or not 0 < len(value) <= MAX_OWNER:
            raise VerificationError('legacy_core_owner_size')
        records.update({owner + '.path': path, owner + '.size': str(len(value)),
                        owner + '.sha256': hashlib.sha256(value).hexdigest()})
    # The nested Native manifest is the source-binding authority for its bytes.
    with zipfile.ZipFile(io.BytesIO(files[OWNERS['native']])) as archive:
        names = entries(archive)
        manifests = [name for name in names if name.startswith('p2wlan/native/') and name.endswith('/manifest.properties')]
        if not 1 <= len(manifests) <= len(PLATFORMS):
            raise VerificationError('legacy_core_native_inventory')
        for name in manifests:
            if name not in resource_names():
                raise VerificationError('legacy_core_native_inventory')
            lines = read(archive, name, 4096).decode('ascii').splitlines()
            fields = {}
            for line in lines:
                key, separator, value = line.partition('=')
                if not separator or key in fields:
                    raise VerificationError('legacy_core_native_manifest')
                fields[key] = value
            if fields.get('source-revision') != source_revision or fields.get('source-sha256') != native_source_sha256:
                raise VerificationError('legacy_core_source_identity')
    value = ''.join(f'{key}={records[key]}\n' for key in sorted(records)).encode('ascii')
    if len(value) > MAX_INDEX:
        raise VerificationError('legacy_core_index_capacity')
    return value


def index_values(value):
    if not isinstance(value, bytes) or not 0 < len(value) <= MAX_INDEX:
        raise VerificationError('legacy_core_index_capacity')
    records = {}
    try:
        for line in value.decode('ascii').splitlines():
            key, separator, field = line.partition('=')
            if not separator or key in records:
                raise VerificationError('legacy_core_index_contract')
            records[key] = field
    except UnicodeError as failure:
        raise VerificationError('legacy_core_index_contract') from failure
    expected = {'schema', 'minecraft', 'forge', 'modlauncher', 'java', 'mod-version', 'source-revision', 'native-source-sha256'} \
        | {owner + '.' + key for owner in OWNERS for key in ('path', 'size', 'sha256')}
    if set(records) != expected:
        raise VerificationError('legacy_core_index_contract')
    fixed = {'schema': '1', 'minecraft': '1.16.5', 'forge': '36.2.42', 'modlauncher': '8.1.3',
             'java': '17', 'mod-version': '0.1.0'}
    if any(records[key] != field for key, field in fixed.items()) \
            or re.fullmatch('[0-9a-f]{40}', records['source-revision']) is None \
            or re.fullmatch('[0-9a-f]{64}', records['native-source-sha256']) is None:
        raise VerificationError('legacy_core_index_contract')
    for owner, path in OWNERS.items():
        if records[owner + '.path'] != path or re.fullmatch('[1-9][0-9]{0,8}', records[owner + '.size']) is None \
                or int(records[owner + '.size']) > MAX_OWNER \
                or re.fullmatch('[0-9a-f]{64}', records[owner + '.sha256']) is None:
            raise VerificationError('legacy_core_index_contract')
    return records
