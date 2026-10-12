#!/usr/bin/env python3
"""Build/check a source-bound CurseForge-format ZIP for HMCL, without game files."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
from types import MappingProxyType
import zipfile

from artifact_checks import MAX_BINARY, PLATFORMS, VerificationError, bind_sources, entries, metadata, read
from verify_mod_jar import verify as verify_mod
from verify_neoforge_jar import verify as verify_neoforge
from verify_forge_jar import verify as verify_forge

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


@dataclass(frozen=True)
class LoaderProfile:
    minecraft: str
    loader: str
    name: str
    version: str
    api_version: str | None = None
    api_sha256: str | None = None

    @property
    def mod_path(self):
        return f"overrides/mods/p2wlan-{self.loader}-{self.minecraft}-{VERSION}.jar"

    @property
    def api_path(self):
        return f"overrides/mods/fabric-api-{self.api_version}.jar" if self.api_version else None


# Only audited source-defined combinations are admitted. A later game version
# needs its own pins and archive contract; callers cannot supply checksums or
# verification functions. The networking/native implementation remains shared.
PROFILES = MappingProxyType({
    (MINECRAFT, "fabric"): LoaderProfile(MINECRAFT, "fabric", "Fabric", LOADER, API_VERSION, API_SHA256),
    (MINECRAFT, "neoforge"): LoaderProfile(MINECRAFT, "neoforge", "NeoForge", "21.1.256"),
    (MINECRAFT, "forge"): LoaderProfile(MINECRAFT, "forge", "Forge", "52.1.16"),
})
VERIFIERS = MappingProxyType({
    (MINECRAFT, "fabric"): verify_mod,
    (MINECRAFT, "neoforge"): verify_neoforge,
    (MINECRAFT, "forge"): verify_forge,
})


def profile(loader="fabric"):
    if not isinstance(loader, str) or (MINECRAFT, loader) not in PROFILES:
        raise VerificationError("pack_loader_unsupported")
    return PROFILES[MINECRAFT, loader]


def payload_names(loader="fabric"):
    selected = profile(loader)
    names = {"manifest.json", selected.mod_path, GUIDE, "overrides/" + GUIDE}
    if selected.api_path:
        names.add(selected.api_path)
    return frozenset(names)


# Existing Python callers retain the Fabric defaults.
PAYLOADS = payload_names()
NAMES = PAYLOADS | {INDEX}


def checksum(value):
    return hashlib.sha256(value).hexdigest()


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def launcher_manifest(platform, loader="fabric"):
    if not isinstance(platform, str) or platform not in PLATFORMS:
        raise VerificationError("pack_platform_unsupported")
    selected = profile(loader)
    return {"manifestType": "minecraftModpack", "manifestVersion": 1,
            "name": f"P2WLAN {selected.name} {selected.minecraft} ({platform})", "version": VERSION,
            "author": "P2WLAN", "minecraft": {"version": selected.minecraft,
                "modLoaders": [{"id": selected.loader + "-" + selected.version, "primary": True}]},
            "files": [], "overrides": "overrides"}


def guide(platform, loader="fabric"):
    launcher_manifest(platform, loader)
    selected = profile(loader)
    label = "Fabric Loader" if loader == "fabric" else selected.name
    dependencies = f" / Fabric API {selected.api_version}" if selected.api_version else ""
    return (f"P2WLAN 实验整合包 — {platform}\r\n"
            f"Minecraft {selected.minecraft} / {label} {selected.version}{dependencies}\r\n"
            "实机验证范围：macOS Apple Silicon 本机离线认证专服及内置世界；Windows/HMCL 与跨机器联机待验证。\r\n"
            "1. 在 HMCL 的整合包安装入口选择本 ZIP，不要把 ZIP 放进 mods 文件夹。\r\n"
            "2. 使用独立游戏实例、64 位操作系统和 64 位 Java 21。\r\n"
            "Windows 目标是 x86-64（Intel/AMD 64 位），实例及临时目录须放在本地 NTFS；不支持 32 位 Java/Windows。\r\n"
            "3. 使用自己的游戏账号启动；包内不含游戏本体、Java、账号或凭据。\r\n"
            "4. 点击菜单按钮旁的方形局域网像素图标打开 P2WLAN，首次填写 Control 地址并登录或注册；以后保留登录会话和地址。\r\n"
            "5. 加入或创建房间后，点击房间名称连接虚拟局域网。\r\n"
            "6. 房主进入单人世界，在暂停菜单的 P2WLAN 面板点击“共享本地世界”；停止时点击“停止共享”。\r\n"
            "朋友连接同一房间后，返回 Minecraft 多人游戏，选择带 P2WLAN 标记的服务器进入。\r\n"
            "退出游戏世界不会断开房间；要断开，请在 P2WLAN 面板点击“断开房间”。\r\n"
            "面板的“显示设置”可开启游戏浮层（默认关闭），选择基础/详细/高级与显示位置。\r\n"
            "“延迟统计”显示最近 60 秒（最多 256 个成功 RTT 样本）的 Min/Avg/Max 与 P50/P90/P95/P99。\r\n"
            "RTT 是 P2WLAN 路径的实测往返时间；无有效样本时显示“未测得”。悬停可看抖动、MTU 和累计流量。\r\n"
            "Control 支持 HTTPS 和 HTTP；优先使用 HTTPS。HTTP 明文传输账号、房间和信令，存在监听与篡改风险。\r\n"
            "若状态显示“启动失败”，登录/注册按钮不会启用；点击“诊断日志”查看阶段、文件操作和系统错误码，再点“复制诊断”反馈。\r\n"
            "日志在当前游戏实例的 logs/p2wlan-mod/startup.log（上一段 startup.log.1）；每段最多 256 KiB。目录不可写仍能复制内存诊断。\r\n"
            "不要发送 config/p2wlan-private、账号密码、token 或未脱敏的启动器日志。诊断不会自动上传。\r\n"
            "P2WLAN-PACK.json 记录构建身份、执行核验方式、文件大小和 SHA-256；wine-build-info 表示在 Wine 中执行构建身份核验。\r\n").encode("utf-8")


def verify_api(value, loader="fabric"):
    selected = profile(loader)
    if selected.api_version is None:
        if value is not None:
            raise VerificationError("pack_fabric_api_unexpected")
        return
    if not isinstance(value, (bytes, bytearray)) or not 0 < len(value) <= MAX_API or checksum(value) != selected.api_sha256:
        raise VerificationError("pack_fabric_api_checksum")
    with zipfile.ZipFile(io.BytesIO(value)) as archive:
        entries(archive)
        info = metadata(archive)
        if info.get("id") != "fabric-api" or info.get("version") != selected.api_version:
            raise VerificationError("pack_fabric_api_contract")


def native_info(value, platform, loader="fabric"):
    launcher_manifest(platform, loader)
    selected = profile(loader)
    # Every verifier requires the complete installed archive, including nested
    # portable/native payloads. A Forge slim/source JAR cannot satisfy it.
    natives = VERIFIERS[selected.minecraft, selected.loader](io.BytesIO(value), False)
    bind_sources(natives, False)
    if platform not in {item["verification-platform"] for item in natives}:
        raise VerificationError("pack_target_native_missing")
    return {"schema": 1, "format": "curseforge-zip", "minecraft": selected.minecraft,
            "loader": {"id": selected.loader, "version": selected.version}, "mod_version": VERSION,
            "platform": platform, "source_revision": natives[0]["source-revision"],
            "native_source_sha256": natives[0]["source-sha256"],
            "native_targets": sorted(item["verification-platform"] for item in natives),
            "native_verification_methods": [{"platform": item["verification-platform"], "method": item["verification-method"]}
                                             for item in sorted(natives, key=lambda item: item["verification-platform"])],
            "verification": "archive-structure/source-binding"}


def file_records(payloads, loader="fabric"):
    return [{"path": name, "size": len(payloads[name]), "sha256": checksum(payloads[name])}
            for name in sorted(payload_names(loader))]


def build_bytes(mod, api, platform, loader="fabric"):
    selected = profile(loader)
    if not 0 < len(mod) <= MAX_BINARY:
        raise VerificationError("pack_mod_size")
    info = native_info(mod, platform, loader)
    verify_api(api, loader)
    instructions = guide(platform, loader)
    payloads = {"manifest.json": encoded(launcher_manifest(platform, loader)), selected.mod_path: mod,
                GUIDE: instructions, "overrides/" + GUIDE: instructions}
    if selected.api_path:
        payloads[selected.api_path] = api
    payloads[INDEX] = encoded(info | {"files": file_records(payloads, loader)})
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
    verify_zip(io.BytesIO(value), platform, loader)
    return value


def verify_zip(source, expected_platform=None, expected_loader=None):
    with zipfile.ZipFile(source) as archive:
        names = entries(archive)
        if INDEX not in names or sum(item.file_size for item in archive.infolist()) > MAX_PACK:
            raise VerificationError("pack_archive_layout")
        if any(item.is_dir() or item.flag_bits & 1
               or item.external_attr >> 16 & 0o170000 not in {0, stat.S_IFREG}
               for item in archive.infolist()):
            raise VerificationError("pack_archive_file_type")
        index = metadata(archive, INDEX)
        loader_info = index.get("loader")
        if not isinstance(loader_info, dict) or set(loader_info) != {"id", "version"}:
            raise VerificationError("pack_metadata_mismatch")
        loader = loader_info["id"]
        selected = profile(loader)
        if loader_info["version"] != selected.version or index.get("minecraft") != selected.minecraft:
            raise VerificationError("pack_metadata_mismatch")
        if expected_loader is not None and selected != profile(expected_loader):
            raise VerificationError("pack_loader_mismatch")
        admitted = payload_names(loader)
        if names != admitted | {INDEX}:
            raise VerificationError("pack_archive_layout")
        platform = index.get("platform")
        launcher_manifest(platform, loader)
        if expected_platform is not None and platform != expected_platform:
            raise VerificationError("pack_target_mismatch")
        payloads = {name: read(archive, name, MAX_BINARY if name == selected.mod_path else MAX_API if name == selected.api_path else 65536)
                    for name in admitted}
        expected = native_info(payloads[selected.mod_path], platform, loader) | {"files": file_records(payloads, loader)}
        # JSON booleans and floats must not masquerade as integer schema/size.
        if encoded(index) != encoded(expected):
            raise VerificationError("pack_metadata_mismatch")
        verify_api(payloads[selected.api_path] if selected.api_path else None, loader)
        if payloads["manifest.json"] != encoded(launcher_manifest(platform, loader)):
            raise VerificationError("pack_launcher_manifest")
        if payloads[GUIDE] != guide(platform, loader) or payloads["overrides/" + GUIDE] != payloads[GUIDE]:
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
    build.add_argument("--fabric-api", type=Path)
    build.add_argument("--loader", choices=("fabric", "neoforge", "forge"), default="fabric")
    build.add_argument("--platform", choices=sorted(PLATFORMS), required=True)
    build.add_argument("--output", type=Path, required=True)
    verify = commands.add_parser("verify")
    verify.add_argument("archive", type=Path)
    verify.add_argument("--loader", choices=("fabric", "neoforge", "forge"), default="fabric")
    verify.add_argument("--platform", choices=sorted(PLATFORMS), required=True)
    args = parser.parse_args()
    if args.command == "build" and (args.loader == "fabric") != (args.fabric_api is not None):
        parser.error("--fabric-api is required only for Fabric packs")
    try:
        if args.command == "build":
            api = regular_bytes(args.fabric_api, MAX_API) if args.fabric_api is not None else None
            value = build_bytes(regular_bytes(args.mod, MAX_BINARY), api, args.platform, args.loader)
            publish(args.output, value)
        else:
            value = regular_bytes(args.archive, MAX_PACK)
        info = verify_zip(io.BytesIO(value), args.platform, args.loader)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        parser.exit(1, f"HMCL pack failed: {str(error) if isinstance(error, VerificationError) else 'PACK_IO'}\n")
    print("PASS HMCL ZIP structure/source binding: " + args.loader + " / " + args.platform)
    print(json.dumps(info | {"artifact_sha256": checksum(value)}, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
