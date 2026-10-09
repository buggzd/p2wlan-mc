#!/usr/bin/env python3
"""Bounded archive, bytecode and source-bound native artifact checks."""
import hashlib
import json
import re
import subprocess
from native_bundle import MAX_BINARY, PLATFORMS, ROOT, source_digest, strict_object, executable_name

FIELDS = {"schema", "protocol", "version", "source-revision", "source-sha256", "build-kind", "os", "arch",
          "executable-sha256", "executable-size", "verification-platform"}


class VerificationError(ValueError):
    pass


def entries(archive):
    values = archive.infolist()
    if len(values) > 512 or len({item.filename for item in values}) != len(values):
        raise VerificationError("mod_archive_entries")
    for item in values:
        if item.file_size > MAX_BINARY or item.filename.startswith("/") or "\\" in item.filename or ".." in item.filename.split("/"):
            raise VerificationError("mod_archive_entry_invalid")
    return {item.filename for item in values}


def read(archive, name, maximum):
    if name not in archive.namelist() or archive.getinfo(name).file_size > maximum:
        raise VerificationError("mod_archive_member_invalid")
    with archive.open(name) as stream:
        value = stream.read(maximum + 1)
    if len(value) > maximum:
        raise VerificationError("mod_archive_member_size")
    return value


def classfile(archive, name, major):
    value = read(archive, name, 1024 * 1024)
    if len(value) < 8 or value[:6] != b"\xca\xfe\xba\xbe\x00\x00" or int.from_bytes(value[6:8], "big") != major:
        raise VerificationError("mod_class_version")
    return value


def metadata(archive, name="fabric.mod.json"):
    value = json.loads(read(archive, name, 65536), object_pairs_hook=strict_object)
    if not isinstance(value, dict):
        raise VerificationError("mod_metadata_invalid")
    return value


# Critical JNA 5.14.0 payloads are byte-bound even when Loom adds Fabric
# metadata to the enclosing dependency jar. This is integrity, not a signature.
JNA_PAYLOADS = {
    "com/sun/jna/Native.class": "527878811901063e116e0e9ac5ae41c4fccd73405931a821ec913f9c2cb4f922",
    "com/sun/jna/win32-x86-64/jnidispatch.dll": "69c45175ecfd25af023f96ac0bb2c45e6a95e3ba8a5a50ee7969ccab14825c44",
}


def jna_payloads(archive):
    for name, expected in JNA_PAYLOADS.items():
        if name not in archive.namelist() or hashlib.sha256(read(archive, name, 1024 * 1024)).hexdigest() != expected:
            raise VerificationError("mod_jna_payload")


def native_manifest(archive, name, version, development):
    raw = read(archive, name, 4096)
    if not raw.endswith(b"\n") or any(value != 10 and not 32 <= value <= 126 for value in raw):
        raise VerificationError("mod_native_manifest")
    pairs = [line.split("=") for line in raw.decode("ascii").splitlines()]
    if any(len(pair) != 2 for pair in pairs) or len(pairs) != len(FIELDS):
        raise VerificationError("mod_native_manifest")
    fields = strict_object(pairs)
    platform = fields.get("os", "") + "-" + fields.get("arch", "")
    if set(fields) != FIELDS or fields["schema"] != "1" or fields["protocol"] != "1" or fields["version"] != version \
            or platform not in PLATFORMS or fields["verification-platform"] != platform \
            or name != f"p2wlan/native/{platform}/manifest.properties" \
            or not re.fullmatch("[0-9a-f]{40}", fields["source-revision"]) \
            or not re.fullmatch("[0-9a-f]{64}", fields["source-sha256"]) \
            or not re.fullmatch("[0-9a-f]{64}", fields["executable-sha256"]) \
            or not re.fullmatch("[1-9][0-9]{0,8}", fields["executable-size"]) \
            or not 0 < int(fields["executable-size"]) <= MAX_BINARY \
            or fields["build-kind"] not in {"release", "development"}:
        raise VerificationError("mod_native_manifest")
    if fields["build-kind"] != "release" and not development:
        raise VerificationError("mod_development_native_disabled")
    binary_name = name.removesuffix("manifest.properties") + executable_name(platform)
    expected_size = int(fields["executable-size"])
    if binary_name not in archive.namelist() or archive.getinfo(binary_name).file_size != expected_size:
        raise VerificationError("mod_native_size")
    digest = hashlib.sha256()
    count = 0
    with archive.open(binary_name) as stream:
        while block := stream.read(32768):
            count += len(block)
            if count > expected_size:
                raise VerificationError("mod_native_size")
            digest.update(block)
    if count != expected_size or digest.hexdigest() != fields["executable-sha256"]:
        raise VerificationError("mod_native_checksum")
    return fields


def bind_sources(natives, development):
    if not development and subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=all"], cwd=ROOT):
        raise VerificationError("mod_release_requires_clean_checkout")
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    digest = source_digest()
    if any(item["source-revision"] != revision or item["source-sha256"] != digest for item in natives):
        raise VerificationError("mod_native_sources_changed_rebuild_bundle")
