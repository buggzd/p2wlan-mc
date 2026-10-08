#!/usr/bin/env python3
"""Generate a checked build-only upstream runtime with an injectable packet port."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tomllib

PROJECT = Path(__file__).resolve().parents[1]
ROOT = PROJECT.parent
DESTINATION = PROJECT / "native/target/upstream-runtime"

class ContractError(RuntimeError):
    pass

def replace_once(source: str, before: str, after: str, label: str) -> str:
    if source.count(before) != 1:
        raise ContractError(f"upstream_contract_changed:{label}")
    return source.replace(before, after, 1)

def adapt_run(source: str) -> str:
    source = replace_once(source,
        "    pub async fn run(&mut self) -> Result<()> {",
        """    pub async fn run(&mut self) -> Result<()> {
        self.run_backend(None).await
    }

    /// Run the unchanged network owner with a user-space packet interface.
    pub async fn run_with_packet_factory(&mut self, factory: mc_embed::PacketFactory) -> Result<()> {
        self.run_backend(Some(factory)).await
    }

    async fn run_backend(&mut self, factory: Option<mc_embed::PacketFactory>) -> Result<()> {
        let userspace = factory.is_some();""", "run-entry")
    source = replace_once(source,
        "        let tun = self.init_tun_with(&virtual_ip, &netmask, self.config.network.mtu)?;",
        """        let tun = if let Some(factory) = factory {
            Some(factory(&self.config)?)
        } else {
            self.init_tun_with(&virtual_ip, &netmask, self.config.network.mtu)?
                .map(mc_embed::PacketInterface::system)
        };""", "packet-interface")
    start = "        // Install overlay route. Route errors are startup-fatal and must not\n"
    end = '        info!("[route] installed {cidr}");'
    if source.count(start) != 1 or source.count(end) != 1:
        raise ContractError("upstream_contract_changed:route-block")
    first = source.index(start)
    last = source.index(end, first) + len(end)
    block = source[first:last]
    source = source[:first] + "        if !userspace {\n" + block + "\n        }" + source[last:]
    return source

def toml_value(value):
    if isinstance(value, str):
        return json.dumps(value)
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, list):
        return "[" + ", ".join(toml_value(item) for item in value) + "]"
    if isinstance(value, dict):
        return "{ " + ", ".join(f"{json.dumps(key)} = {toml_value(item)}" for key, item in value.items()) + " }"
    raise ContractError("unsupported_manifest_value")

def manifest(root: Path, destination: Path) -> str:
    workspace = tomllib.loads((root / "Cargo.toml").read_text())["workspace"]
    upstream = tomllib.loads((root / "client/daemon/Cargo.toml").read_text())
    lines = ["[package]", 'name = "p2wlan-mc-runtime"',
        f'version = {toml_value(workspace["package"]["version"])}',
        f'edition = {toml_value(workspace["package"]["edition"])}',
        f'license = {toml_value(workspace["package"]["license"])}',
        'autobins = false', 'autotests = false', "", "[lib]", 'name = "p2pnet_daemon"',
        'path = "src/lib.rs"', "", "[workspace]", ""]
    def section(name, values):
        lines.append(f"[{name}]")
        for key, item in values.items():
            if isinstance(item, dict):
                item = item.copy()
                if item.pop("workspace", False):
                    inherited = workspace["dependencies"][key]
                    inherited = {"version": inherited} if isinstance(inherited, str) else inherited.copy()
                    inherited["features"] = sorted(set(inherited.get("features", [])) | set(item.get("features", [])))
                    item = inherited | {k: v for k, v in item.items() if k != "features"}
                if "path" in item:
                    target = (PROJECT / "native/bridge") if key == "p2wlan-mc-bridge" else (root / "client/daemon" / item["path"]).resolve()
                    item["path"] = os.path.relpath(target.resolve(), destination.resolve())
            lines.append(f"{json.dumps(key)} = {toml_value(item)}")
        lines.append("")
    dependencies = upstream["dependencies"] | {"async-trait": "0.1",
        "p2wlan-mc-bridge": {"path": os.path.relpath((PROJECT / "native/bridge").resolve(), destination.resolve())}}
    section("dependencies", dependencies)
    for target, tables in upstream.get("target", {}).items():
        for name, values in tables.items():
            section(f"target.{json.dumps(target)}.{name}", values)
    return "\n".join(lines)

def write_changed(path: Path, data: bytes):
    if path.is_symlink():
        raise ContractError("generated_path_is_symlink")
    if not path.exists() or path.read_bytes() != data:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

def prepare(root: Path = ROOT, destination: Path = DESTINATION) -> Path:
    upstream = root / "client/daemon"
    # Validate all anchors before producing any output. An upstream change must
    # never silently select a nearby function or an experimental data path.
    run = adapt_run((upstream / "src/lib/daemon/run.rs").read_text())
    dataplane = replace_once((upstream / "src/lib/daemon/dataplane_tasks.rs").read_text(),
        "        tun: Option<TunDevice>,", "        tun: Option<mc_embed::PacketInterface>,", "dataplane-type")
    library = (upstream / "src/lib.rs").read_text()
    if "pub mod mc_embed" in library:
        raise ContractError("upstream_already_defines_packet_adapter")
    library += "\n// Build-only Minecraft port; the upstream source is never edited.\npub mod mc_embed;\n"
    if destination.is_symlink():
        raise ContractError("generated_root_is_symlink")
    # Inspect every existing descendant before writes; an ignored cache must
    # never redirect generated output into the upstream source tree.
    if destination.exists():
        for current, directories, files in os.walk(destination, followlinks=False):
            for name in directories + files:
                if (Path(current) / name).is_symlink():
                    raise ContractError("generated_descendant_is_symlink")
    inputs = {}
    for path in sorted((upstream / "src").rglob("*.rs")):
        relative = path.relative_to(upstream)
        data = path.read_bytes()
        inputs[relative.as_posix()] = hashlib.sha256(data).hexdigest()
        write_changed(destination / relative, data)
    write_changed(destination / "src/lib.rs", library.encode())
    write_changed(destination / "src/lib/daemon/run.rs", run.encode())
    write_changed(destination / "src/lib/daemon/dataplane_tasks.rs", dataplane.encode())
    adapter = (PROJECT / "native/runtime-overlay/mc_embed.rs").read_bytes()
    write_changed(destination / "src/mc_embed.rs", adapter)
    write_changed(destination / "build.rs", (upstream / "build.rs").read_bytes())
    write_changed(destination / "Cargo.toml", manifest(root, destination).encode())
    metadata = {"schema": 1, "source_sha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "upstream_sources": inputs, "adapter_sha256": hashlib.sha256(adapter).hexdigest()}
    write_changed(destination / "UPSTREAM.json", (json.dumps(metadata, sort_keys=True, indent=2) + "\n").encode())
    return destination

if __name__ == "__main__":
    prepare()
