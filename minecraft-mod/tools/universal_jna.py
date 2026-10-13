"""Isolate JNA 5.14 JNI lookup without changing its Java packages or API."""
import hashlib
import io
import json
import zipfile

from artifact_checks import VerificationError, entries, read
from native_bundle import strict_object

NATIVE = 'com/sun/jna/Native.class'
ORIGINAL_PREFIX = 'com/sun/jna/'
ISOLATED_PREFIX = 'p2wlan/jna/'
ORIGINAL_NATIVE_SHA256 = '527878811901063e116e0e9ac5ae41c4fccd73405931a821ec913f9c2cb4f922'
ORIGINAL_CLASS_COUNT = 125
ORIGINAL_CLASS_SHA256 = '45313ba6186536161d9efe2ca1e279738eace5b65e411c1c36b2626357649071'
MAX_ARCHIVE = 8 * 1024 * 1024
MAX_TOTAL = 16 * 1024 * 1024
MAX_MEMBER = 1024 * 1024
JNI_SHA256 = {
 'com/sun/jna/aix-ppc/libjnidispatch.a': '993d6799faa5e2c6363e99050c65ef6efea10059b3a07b80d3854d6d3142ca1a',
 'com/sun/jna/aix-ppc64/libjnidispatch.a': '66a4b348b959d26a143ce54277330d767ae01f90e36308df09d5d110696445df',
 'com/sun/jna/darwin-aarch64/libjnidispatch.jnilib': '22139ef060538cccc0e654639ef36d53a96b4f5d8f0b5027116e430fed5e75df',
 'com/sun/jna/darwin-x86-64/libjnidispatch.jnilib': '12d88138ce3c0a20d4bb3cfbd091b7ccb1f6365a79ab07b2d786a52d104d418e',
 'com/sun/jna/freebsd-x86-64/libjnidispatch.so': '13281e25bf8614ab1aeb5f93d32ce4af02d986b0ca49f7692d08fce981eb1d5e',
 'com/sun/jna/freebsd-x86/libjnidispatch.so': 'c1d200c601baf9f26db1b0ba0d152fb517dd929f01cbbe1ac3320bbe40adb827',
 'com/sun/jna/linux-aarch64/libjnidispatch.so': '5c894177c2c4a1a422047886f5f526959e4f1a8c1561bb3cb2277fe0da85b744',
 'com/sun/jna/linux-arm/libjnidispatch.so': '00a35890dde5b4174d6646d1ebdc553bac3b7d99c1a793899eddaa002b5a16a2',
 'com/sun/jna/linux-armel/libjnidispatch.so': '257d627bce7ddccf539f8a32dd293e4d0ed8ba64070dab07d5ae40d4932d60a6',
 'com/sun/jna/linux-loongarch64/libjnidispatch.so': '2e91afb910bf56ca578786861fb87845f7bae1dc91b3ec6374540f3a946f170a',
 'com/sun/jna/linux-mips64el/libjnidispatch.so': '5e7a37f9cc6c0dc7cfa2b6d0944788c448ff2e87d4aab894a241269a8b6335ed',
 'com/sun/jna/linux-ppc/libjnidispatch.so': 'c974c4ec9701655a30d7601389c3b3246447fdbc90240e809b37cb283224a2ff',
 'com/sun/jna/linux-ppc64le/libjnidispatch.so': 'bb026410e75b3e928cb3ccddc0f9418e3fb40aad1f6c80cf046b72d62e5eb062',
 'com/sun/jna/linux-riscv64/libjnidispatch.so': '938f42166508c30d5b5ed3eb094219f9b2df736b1ea38ed7586c0ce79c12ee97',
 'com/sun/jna/linux-s390x/libjnidispatch.so': '3f33fab801dc3a43a5d269fa0e8f43e54b0f7e29f02c4821901c62aafb3aee6c',
 'com/sun/jna/linux-x86-64/libjnidispatch.so': 'c0ff03e4593fedd2fa96bd76a66ee9dab7a057df8739a7a38133cb5f21d12552',
 'com/sun/jna/linux-x86/libjnidispatch.so': 'caf3d95b12a6f907fa0b647e1d785c6505415fc266e11733b24eec22da6ae3d9',
 'com/sun/jna/openbsd-x86-64/libjnidispatch.so': 'f15ad5da6b5ab33a9fe51992247fa3fe4da6a6ec0a4678d186611bb1258b4fff',
 'com/sun/jna/openbsd-x86/libjnidispatch.so': 'c99db52a1c9ffd791ec0dda9b39ad8aec0b7514dce881e230882210dfc9d9fb7',
 'com/sun/jna/sunos-sparc/libjnidispatch.so': '58e637222be67679df8f2473967555b9697908e65ca2ddf32719d1d5e35769db',
 'com/sun/jna/sunos-sparcv9/libjnidispatch.so': '58e637222be67679df8f2473967555b9697908e65ca2ddf32719d1d5e35769db',
 'com/sun/jna/sunos-x86-64/libjnidispatch.so': '7af04db1da25981579214bdcbf761a8cd1ac5a76c9ac95fc226303ee65dfaeba',
 'com/sun/jna/sunos-x86/libjnidispatch.so': '7af04db1da25981579214bdcbf761a8cd1ac5a76c9ac95fc226303ee65dfaeba',
 'com/sun/jna/win32-aarch64/jnidispatch.dll': 'b9d1479b9619b7ece4a36b6ae31365ffaf15a1355d4f6da02f8b5f09df2fa82f',
 'com/sun/jna/win32-x86-64/jnidispatch.dll': '69c45175ecfd25af023f96ac0bb2c45e6a95e3ba8a5a50ee7969ccab14825c44',
 'com/sun/jna/win32-x86/jnidispatch.dll': '38650a0612730c52580c9f32ff766b44b1c5a426d52e7dd7a53687bf3389ac2c',
}
# Licenses and upstream package/version attributes stay byte-identical.
METADATA_SHA256 = {
 'META-INF/MANIFEST.MF': '39e86b7cf95a1039282b79b47da213a6545638d0b40f357884fee34af527e15d',
 'META-INF/AL2.0': '0d542e0c8804e39aa7f37eb00da5a762149dc682d7829451287e11b938e94594',
 'META-INF/LGPL2.1': 'eea173a556abac0370461e57e12aab266894ea6be3874c2be05fd87871f75449',
 'META-INF/LICENSE': '521bb271ac56e0e29a1b1b688b94af17d00d378fc8e63478d8c8b2a7c4a229d0',
}
FABRIC_METADATA = {'schemaVersion': 1, 'id': 'net_java_dev_jna_jna', 'version': '5.14.0',
                   'name': 'jna', 'custom': {'fabric-loom:generated': True}}


def isolated_resources():
    return {ISOLATED_PREFIX + name.removeprefix(ORIGINAL_PREFIX): digest for name, digest in JNI_SHA256.items()}


def class_digest(files):
    digest = hashlib.sha256()
    for name in sorted(files):
        encoded = name.encode('ascii')
        value = files[name]
        digest.update(len(encoded).to_bytes(2, 'big') + encoded + len(value).to_bytes(4, 'big') + hashlib.sha256(value).digest())
    return digest.hexdigest()


def _rewrite(value, before, after):
    """Change one CONSTANT_String's exact UTF-8; indices and class body stay intact."""
    if not isinstance(value, bytes) or not 24 <= len(value) <= MAX_MEMBER or value[:8] != b'\xca\xfe\xba\xbe\0\0\0\x34':
        raise VerificationError('universal_jna_class_header')
    count = int.from_bytes(value[8:10], 'big')
    result = bytearray(value[:10]); position = 10; index = 1; changed = []; strings = []
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
             12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        if position >= len(value): raise VerificationError('universal_jna_constant_pool')
        tag = value[position]; position += 1; result.append(tag)
        if tag == 1:
            if position + 2 > len(value): raise VerificationError('universal_jna_constant_pool')
            length = int.from_bytes(value[position:position + 2], 'big'); position += 2
            if position + length > len(value): raise VerificationError('universal_jna_constant_pool')
            constant = value[position:position + length]; position += length
            if constant == after: raise VerificationError('universal_jna_prefix_already_present')
            if constant == before: changed.append(index); constant = after
            result.extend(len(constant).to_bytes(2, 'big')); result.extend(constant)
        else:
            size = sizes.get(tag)
            if size is None or position + size > len(value) or tag in (5, 6) and index + 1 >= count:
                raise VerificationError('universal_jna_constant_pool')
            if tag == 8: strings.append(int.from_bytes(value[position:position + 2], 'big'))
            result.extend(value[position:position + size]); position += size
            if tag in (5, 6): index += 1
        index += 1
    if len(changed) != 1 or strings.count(changed[0]) != 1 or position + 14 > len(value):
        raise VerificationError('universal_jna_prefix_contract')
    result.extend(value[position:])
    return bytes(result)


def _members(value, isolated):
    if not isinstance(value, bytes) or not 0 < len(value) <= MAX_ARCHIVE:
        raise VerificationError('universal_jna_archive_capacity')
    try:
        with zipfile.ZipFile(io.BytesIO(value)) as archive:
            names = entries(archive)
            if sum(member.file_size for member in archive.infolist()) > MAX_TOTAL:
                raise VerificationError('universal_jna_archive_capacity')
            files = {name: read(archive, name, MAX_MEMBER) for name in names if not name.endswith('/')}
    except zipfile.BadZipFile as failure:
        raise VerificationError('universal_jna_archive_contract') from failure
    classes = {name: data for name, data in files.items() if name.endswith('.class')}
    if any(not name.startswith(ORIGINAL_PREFIX) for name in classes) or NATIVE not in classes:
        raise VerificationError('universal_jna_class_inventory')
    original = _rewrite(classes[NATIVE], ('/' + ISOLATED_PREFIX).encode(), ('/' + ORIGINAL_PREFIX).encode()) if isolated else classes[NATIVE]
    if hashlib.sha256(original).hexdigest() != ORIGINAL_NATIVE_SHA256:
        raise VerificationError('universal_jna_native_identity')
    normalized = classes | {NATIVE: original}
    if len(normalized) != ORIGINAL_CLASS_COUNT or class_digest(normalized) != ORIGINAL_CLASS_SHA256:
        raise VerificationError('universal_jna_class_inventory')
    resources = isolated_resources() if isolated else JNI_SHA256
    allowed = set(classes) | set(resources) | set(METADATA_SHA256) | {'fabric.mod.json'}
    if set(files) - allowed or not (set(resources) | set(METADATA_SHA256)) <= set(files):
        raise VerificationError('universal_jna_resource_inventory')
    directories = {name[:offset + 1] for name in allowed for offset, char in enumerate(name) if char == '/'}
    if {name for name in names if name.endswith('/')} - directories:
        raise VerificationError('universal_jna_resource_inventory')
    for name, digest in (resources | METADATA_SHA256).items():
        if hashlib.sha256(files[name]).hexdigest() != digest:
            raise VerificationError('universal_jna_resource_identity')
    if 'fabric.mod.json' in files:
        try: metadata = json.loads(files['fabric.mod.json'], object_pairs_hook=strict_object)
        except (ValueError, UnicodeError) as failure: raise VerificationError('universal_jna_fabric_metadata') from failure
        if json.dumps(metadata, sort_keys=True) != json.dumps(FABRIC_METADATA, sort_keys=True):
            raise VerificationError('universal_jna_fabric_metadata')
    return files


def isolate(value):
    """Accept only original JNA 5.14 bytes; return a deterministic single-owner JAR."""
    files = _members(value, False)
    files[NATIVE] = _rewrite(files[NATIVE], ('/' + ORIGINAL_PREFIX).encode(), ('/' + ISOLATED_PREFIX).encode())
    for name in JNI_SHA256:
        files[ISOLATED_PREFIX + name.removeprefix(ORIGINAL_PREFIX)] = files.pop(name)
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16; archive.writestr(info, data)
    result = output.getvalue(); verify(result)
    return result


def verify(value):
    """Prove exact reversible Native.class change and all unchanged JNI payloads."""
    files = _members(value, True)
    return {name: hashlib.sha256(files[name]).hexdigest() for name in isolated_resources()}
