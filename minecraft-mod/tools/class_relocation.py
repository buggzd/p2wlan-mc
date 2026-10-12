#!/usr/bin/env python3
"""Relocate the owned Fabric game namespace through classfile constants only."""
import argparse
import io
import json
from pathlib import Path
import zipfile

from artifact_checks import MAX_BINARY, VerificationError, entries, read
from native_bundle import strict_object

OLD = "io/p2wlan/mc/game/"
NEW = "io/p2wlan/mc/fabric/game/"
LEGACY = "io/p2wlan/mc/game/v1_20_1/"
FABRIC_LEGACY = "io/p2wlan/mc/fabric/game/v1_20_1/"
REFMAPS = {
    "p2wlan-fabric-1.21.1-refmap.json": "p2wlan-universal-fabric-server-refmap.json",
    "client-p2wlan-fabric-1.21.1-refmap.json": "p2wlan-universal-fabric-client-refmap.json",
}
CONFIGS = {"p2wlan.mixins.json", "p2wlan.client.mixins.json"}
MAX_CLASS = 1024 * 1024


def _target(destination):
    if destination not in {NEW, LEGACY, FABRIC_LEGACY}:
        raise VerificationError("relocation_namespace")
    return destination


def _replace(value, before, after):
    # The version namespace is inside the original owned package. Preserve an
    # already relocated segment rather than adding the version a second time.
    return after.join(part.replace(before, after) for part in value.split(after))


def relocate_name(value, destination=NEW):
    destination = _target(destination)
    return _replace(_replace(value, OLD, destination), OLD.replace("/", "."), destination.replace("/", "."))


def relocate_class(value: bytes, destination=NEW) -> bytes:
    # Descriptors, signatures, annotations and reflection names all refer to
    # CONSTANT_Utf8 entries. Keep constant indices and the class body intact;
    # never byte-replace instructions, lengths or arbitrary archive members.
    destination = _target(destination)
    if not isinstance(value, bytes) or not 10 <= len(value) <= MAX_CLASS \
            or value[:4] != b"\xca\xfe\xba\xbe":
        raise VerificationError("relocation_class_header")
    count = int.from_bytes(value[8:10], "big")
    if count < 2:
        raise VerificationError("relocation_constant_pool")
    result = bytearray(value[:10])
    position = 10
    index = 1
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
             12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        if position >= len(value):
            raise VerificationError("relocation_constant_pool")
        tag = value[position]
        position += 1
        result.append(tag)
        if tag == 1:
            if position + 2 > len(value):
                raise VerificationError("relocation_constant_pool")
            length = int.from_bytes(value[position:position + 2], "big")
            position += 2
            if position + length > len(value):
                raise VerificationError("relocation_constant_pool")
            # Preserve modified UTF-8 bytes, including NUL/surrogate encoding.
            constant = value[position:position + length]
            position += length
            for old, new in ((OLD, destination), (OLD.replace("/", "."), destination.replace("/", "."))):
                constant = _replace(constant, old.encode("ascii"), new.encode("ascii"))
            if len(constant) > 65535:
                raise VerificationError("relocation_constant_size")
            result.extend(len(constant).to_bytes(2, "big"))
            result.extend(constant)
        else:
            size = sizes.get(tag)
            if size is None or position + size > len(value) \
                    or tag in (5, 6) and index + 1 >= count:
                raise VerificationError("relocation_constant_pool")
            result.extend(value[position:position + size])
            position += size
            if tag in (5, 6):
                index += 1
        index += 1
    if position + 14 > len(value):
        raise VerificationError("relocation_class_body")
    result.extend(value[position:])
    if len(result) > MAX_CLASS:
        raise VerificationError("relocation_class_size")
    return bytes(result)


def relocate_keys(value):
    if isinstance(value, dict):
        result = {}
        for key, child in value.items():
            target = relocate_name(key)
            if target in result:
                raise VerificationError("relocation_json_key_collision")
            result[target] = relocate_keys(child)
        return result
    if isinstance(value, list):
        return [relocate_keys(child) for child in value]
    return value


def relocate_member(name, value):
    # The neutral bootstrap selects both families/versions. Its package strings
    # describe the dispatch table and are not Fabric game references.
    if name.startswith("io/p2wlan/mc/bootstrap/") and name.endswith(".class"):
        return name, value
    if name.endswith(".class"):
        return relocate_name(name), relocate_class(value)
    if name in CONFIGS or name in REFMAPS:
        if len(value) > 65536:
            raise VerificationError("relocation_json_size")
        item = json.loads(value, object_pairs_hook=strict_object)
        if not isinstance(item, dict):
            raise VerificationError("relocation_json_object")
        if name in CONFIGS:
            if not isinstance(item.get("package"), str) or item.get("refmap") not in REFMAPS:
                raise VerificationError("relocation_mixin_config")
            item["package"] = relocate_name(item["package"])
            item["refmap"] = REFMAPS[item["refmap"]]
        else:
            item = relocate_keys(item)
        return REFMAPS.get(name, name), (json.dumps(item, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
    return name, value


def relocate_fabric(source, development=False):
    from verify_mod_jar import verify
    path = Path(source)
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAX_BINARY:
        raise VerificationError("relocation_archive_size")
    verify(source, development)
    output = io.BytesIO()
    with zipfile.ZipFile(source) as archive, zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as target:
        names = entries(archive)
        if sum(item.file_size for item in archive.infolist()) > MAX_BINARY:
            raise VerificationError("relocation_archive_size")
        seen = set()
        for name in sorted(names):
            destination, value = relocate_member(name, read(archive, name, MAX_BINARY))
            if destination in seen:
                raise VerificationError("relocation_archive_collision")
            seen.add(destination)
            info = zipfile.ZipInfo(destination, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            target.writestr(info, value)
    return output.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fabric", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--development", action="store_true")
    args = parser.parse_args()
    try:
        from artifact_checks import bind_sources
        from verify_mod_jar import verify
        bind_sources(verify(args.fabric, args.development), args.development)
        result = relocate_fabric(args.fabric, args.development)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(result)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Fabric relocation failed: {str(error) if isinstance(error, VerificationError) else 'RELOCATION_IO'}\n")
    print("PASS Fabric owned class/refmap relocation")


if __name__ == "__main__":
    main()
