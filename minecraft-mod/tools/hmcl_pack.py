#!/usr/bin/env python3
"""Build/check a source-bound CurseForge-format ZIP for HMCL, without game files."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import zipfile

from artifact_checks import MAX_BINARY, PLATFORMS, VerificationError, bind_sources, entries, metadata, read
from verify_mod_jar import verify as verify_mod

MINECRAFT = "1.21.1"
LOADER = "0.16.14"
VERSION = "0.1.0"
API_VERSION = "0.116.7+1.21.1"
# The fixed official Fabric Maven artifact, not a remapped/sources jar. No
# unverified download, caller-supplied checksum, or development-mode escape.
API_SHA256 = "08018cc48c97415a38016a00dbd5a2c7a460ba6b7a051690a8cb3dbb5e8482a4"
MAX_API = 8 * 1024 * 1024
MAX_PACK = MAX_BINARY + 16 * 1024 * 1024
MOD = f"overrides/mods/p2wlan-fabric-{MINECRAFT}-{VERSION}.jar"
API = f"overrides/mods/fabric-api-{API_VERSION}.jar"
GUIDE = "P2WLAN-安装说明.txt"
INDEX = "P2WLAN-PACK.json"
PAYLOADS = {"manifest.json", MOD, API, GUIDE, "overrides/" + GUIDE}
NAMES = PAYLOADS | {INDEX}


def checksum(value):
    return hashlib.sha256(value).hexdigest()


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def launcher_manifest(platform):
    if not isinstance(platform, str) or platform not in PLATFORMS:
        raise VerificationError("pack_platform_unsupported")
    return {"manifestType": "minecraftModpack", "manifestVersion": 1,
            "name": f"P2WLAN Fabric {MINECRAFT} ({platform})", "version": VERSION,
            "author": "P2WLAN", "minecraft": {"version": MINECRAFT,
                "modLoaders": [{"id": "fabric-" + LOADER, "primary": True}]},
            "files": [], "overrides": "overrides"}


def guide(platform):
    launcher_manifest(platform)
    return (f"P2WLAN 实验整合包 — {platform}\r\n"
            f"Minecraft {MINECRAFT} / Fabric Loader {LOADER} / Fabric API {API_VERSION}\r\n"
            "1. 在 HMCL 的整合包安装入口选择本 ZIP，不要把 ZIP 放进 mods 文件夹。\r\n"
            "2. 使用独立游戏实例、64 位操作系统和 64 位 Java 21。\r\n"
            "Windows 目标是 x86-64（Intel/AMD 64 位），实例及临时目录须放在本地 NTFS；不支持 32 位 Java/Windows。\r\n"
            "3. 使用自己的游戏账号启动；包内不含游戏本体、Java、账号或凭据。\r\n"
            "4. 首次打开 P2WLAN，填写 Control 地址并登录或注册；以后保留登录会话和地址。\r\n"
            "5. 加入或创建房间后，点击房间名称连接虚拟局域网。\r\n"
            "6. 返回 Minecraft 多人游戏，选择带 P2WLAN 标记的服务器进入。\r\n"
            "退出游戏世界不会断开房间；要断开，请在 P2WLAN 面板点击“断开房间”。\r\n"
            "悬停指标可看 RTT、抖动、MTU 和累计流量；未测得不等于 0 ms。\r\n"
            "HTTP 可以使用，但账号、房间和信令可能被监听或篡改；界面只提示风险。\r\n"
            "若状态显示“启动失败”，登录/注册按钮不会启用；悬停右上状态查看 IPC 错误码，请反馈完整错误码及退出码（若有），不要发送密码或配置文件。\r\n"
            "P2WLAN-PACK.json 记录原生程序构建身份的执行核验方式。\r\n"
            "Wine 构建身份核验不是 Windows 设备测试；摘要也不是签名或双机/公网游戏验收。\r\n").encode("utf-8")


def verify_api(value):
    if not 0 < len(value) <= MAX_API or checksum(value) != API_SHA256:
        raise VerificationError("pack_fabric_api_checksum")
    with zipfile.ZipFile(io.BytesIO(value)) as archive:
        entries(archive)
        info = metadata(archive)
        if info.get("id") != "fabric-api" or info.get("version") != API_VERSION:
            raise VerificationError("pack_fabric_api_contract")


def native_info(value, platform):
    launcher_manifest(platform)
    natives = verify_mod(io.BytesIO(value))  # Release-only, including nested native hashes.
    bind_sources(natives, False)
    if platform not in {item["verification-platform"] for item in natives}:
        raise VerificationError("pack_target_native_missing")
    return {"schema": 1, "format": "curseforge-zip", "minecraft": MINECRAFT,
            "loader": {"id": "fabric", "version": LOADER}, "mod_version": VERSION,
            "platform": platform, "source_revision": natives[0]["source-revision"],
            "native_source_sha256": natives[0]["source-sha256"],
            "native_targets": sorted(item["verification-platform"] for item in natives),
            "native_verification_methods": [{"platform": item["verification-platform"], "method": item["verification-method"]}
                                             for item in sorted(natives, key=lambda item: item["verification-platform"])],
            "verification": "archive-structure/source-binding"}


def file_records(payloads):
    return [{"path": name, "size": len(payloads[name]), "sha256": checksum(payloads[name])}
            for name in sorted(PAYLOADS)]


def build_bytes(mod, api, platform):
    if not 0 < len(mod) <= MAX_BINARY:
        raise VerificationError("pack_mod_size")
    info = native_info(mod, platform)
    verify_api(api)
    instructions = guide(platform)
    payloads = {"manifest.json": encoded(launcher_manifest(platform)), MOD: mod, API: api,
                GUIDE: instructions, "overrides/" + GUIDE: instructions}
    payloads[INDEX] = encoded(info | {"files": file_records(payloads)})
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name in sorted(payloads):
            item = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            item.create_system = 3
            item.external_attr = (stat.S_IFREG | 0o644) << 16
            item.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(item, payloads[name])
    value = output.getvalue()
    if len(value) > MAX_PACK:
        raise VerificationError("pack_archive_size")
    # Re-read exactly the bytes that will be published, and recheck HEAD and
    # source digest after construction. A source change cannot keep stale proof.
    verify_zip(io.BytesIO(value), platform)
    return value


def verify_zip(source, expected_platform=None):
    with zipfile.ZipFile(source) as archive:
        if entries(archive) != NAMES or sum(item.file_size for item in archive.infolist()) > MAX_PACK:
            raise VerificationError("pack_archive_layout")
        if any(item.is_dir() or item.flag_bits & 1
               or item.external_attr >> 16 & 0o170000 not in {0, stat.S_IFREG}
               for item in archive.infolist()):
            raise VerificationError("pack_archive_file_type")
        index = metadata(archive, INDEX)
        platform = index.get("platform")
        launcher_manifest(platform)
        if expected_platform is not None and platform != expected_platform:
            raise VerificationError("pack_target_mismatch")
        payloads = {name: read(archive, name, MAX_BINARY if name == MOD else MAX_API if name == API else 65536)
                    for name in PAYLOADS}
        expected = native_info(payloads[MOD], platform) | {"files": file_records(payloads)}
        # JSON booleans and floats must not masquerade as integer schema/size.
        if encoded(index) != encoded(expected):
            raise VerificationError("pack_metadata_mismatch")
        verify_api(payloads[API])
        if payloads["manifest.json"] != encoded(launcher_manifest(platform)):
            raise VerificationError("pack_launcher_manifest")
        if payloads[GUIDE] != guide(platform) or payloads["overrides/" + GUIDE] != payloads[GUIDE]:
            raise VerificationError("pack_install_guide")
        return expected


def regular_bytes(path, maximum):
    # One bounded snapshot feeds checking, hashing, and packaging. Never copy a
    # later path lookup's bytes after validating an earlier file's metadata.
    initial = path.lstat()
    if not stat.S_ISREG(initial.st_mode) or not 0 < initial.st_size <= maximum:
        raise VerificationError("pack_input_file")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0) | getattr(os, "O_BINARY", 0)
    descriptor = os.open(path, flags)
    with os.fdopen(descriptor, "rb") as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or not 0 < before.st_size <= maximum \
                or (initial.st_dev, initial.st_ino) != (before.st_dev, before.st_ino):
            raise VerificationError("pack_input_file")
        value = stream.read(maximum + 1)
        after = os.fstat(stream.fileno())
        if len(value) != before.st_size or (before.st_size, before.st_mtime_ns, before.st_ctime_ns) != \
                (after.st_size, after.st_mtime_ns, after.st_ctime_ns):
            raise VerificationError("pack_input_changed")
        return value


def publish(output, value):
    # A same-directory staging file plus create-only hard link gives an atomic
    # publication without overwriting user files. One staging file per run; it
    # is removed by its own context, never recursive cleanup of game directories.
    descriptor, name = tempfile.mkstemp(prefix=".p2wlan-pack-", suffix=".tmp", dir=output.parent)
    identity = os.fstat(descriptor)
    try:
        with os.fdopen(descriptor, "wb") as staging:
            staging.write(value)
            staging.flush()
            os.fsync(staging.fileno())
        # Windows must close the writer before creating the publication link.
        actual = os.lstat(name)
        if (actual.st_dev, actual.st_ino) != (identity.st_dev, identity.st_ino) or not stat.S_ISREG(actual.st_mode):
            raise VerificationError("pack_staging_changed")
        try:
            os.link(name, output)
        except FileExistsError:
            if regular_bytes(output, MAX_PACK) != value:
                raise VerificationError("pack_output_exists")
    finally:
        actual = os.lstat(name)
        if (actual.st_dev, actual.st_ino) != (identity.st_dev, identity.st_ino) or not stat.S_ISREG(actual.st_mode):
            raise VerificationError("pack_staging_changed")
        os.unlink(name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build")
    build.add_argument("--mod", type=Path, required=True)
    build.add_argument("--fabric-api", type=Path, required=True)
    build.add_argument("--platform", choices=sorted(PLATFORMS), required=True)
    build.add_argument("--output", type=Path, required=True)
    verify = commands.add_parser("verify")
    verify.add_argument("archive", type=Path)
    verify.add_argument("--platform", choices=sorted(PLATFORMS), required=True)
    args = parser.parse_args()
    try:
        if args.command == "build":
            value = build_bytes(regular_bytes(args.mod, MAX_BINARY), regular_bytes(args.fabric_api, MAX_API), args.platform)
            publish(args.output, value)
        else:
            value = regular_bytes(args.archive, MAX_PACK)
        info = verify_zip(io.BytesIO(value), args.platform)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        parser.exit(1, f"HMCL pack failed: {str(error) if isinstance(error, VerificationError) else 'PACK_IO'}\n")
    print("PASS HMCL ZIP structure/source binding: " + args.platform)
    print(json.dumps(info | {"artifact_sha256": checksum(value)}, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
