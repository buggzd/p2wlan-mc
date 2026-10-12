"""Shared bounded Fabric nested common, transport, native and JNA policy."""
import io
import zipfile

from artifact_checks import (MAX_BINARY, PLATFORMS, VerificationError, classfile,
                             entries, executable_name, jna_payloads, native_manifest, read)

COMMON = "io/p2wlan/mc/common/BundledAgent.class"
TRANSPORT = tuple(f"io/p2wlan/mc/transport/{name}.class" for name in
                  ("LoopbackBind", "LoopbackAdmission", "ProxyPrelude", "ScopedClientConnect"))


def portable(archive, info, development):
    children = info.get("jars")
    if not isinstance(children, list) or not 1 <= len(children) <= 8:
        raise VerificationError("mod_nested_resources_missing")
    common_count = 0
    transport_count = 0
    jna_count = 0
    natives = []
    child_names = set()
    for item in children:
        if not isinstance(item, dict) or set(item) != {"file"} or not isinstance(item["file"], str) \
                or not item["file"].startswith("META-INF/jars/") or item["file"] in child_names:
            raise VerificationError("mod_nested_resource_invalid")
        child_names.add(item["file"])
        with zipfile.ZipFile(io.BytesIO(read(archive, item["file"], MAX_BINARY))) as child:
            names = entries(child)
            if "com/sun/jna/Native.class" in names:
                jna_payloads(child)
                jna_count += 1
            if TRANSPORT[0] in names:
                transport_count += 1
                for name in TRANSPORT:
                    classfile(child, name, 61)
            if COMMON in names or TRANSPORT[0] in names:
                if COMMON in names:
                    common_count += 1
                for name in names:
                    if name.endswith(".class"):
                        value = classfile(child, name, 61)
                        if any(prefix in value for prefix in (b"net/minecraft/", b"net/fabricmc/", b"net/neoforged/", b"net/minecraftforge/")):
                            raise VerificationError("mod_common_game_dependency")
            manifests = sorted(name for name in names if name.startswith("p2wlan/native/") and name.endswith("/manifest.properties"))
            resources = [native_manifest(child, name, info["version"], development) for name in manifests]
            admitted = set(manifests) | {f"p2wlan/native/{item['verification-platform']}/{executable_name(item['verification-platform'])}" for item in resources}
            if any(name.startswith("p2wlan/native/") and not name.endswith("/") and name not in admitted for name in names):
                raise VerificationError("mod_native_resource_unexpected")
            natives.extend(resources)
    platforms = [item["verification-platform"] for item in natives]
    if jna_count != 1:
        raise VerificationError("mod_jna_resources_ambiguous")
    if transport_count != 1:
        raise VerificationError("mod_shared_transport_resources_ambiguous")
    if common_count != 1 or not natives or len(platforms) > len(PLATFORMS) or len(set(platforms)) != len(platforms):
        raise VerificationError("mod_native_resources_ambiguous")
    return natives
