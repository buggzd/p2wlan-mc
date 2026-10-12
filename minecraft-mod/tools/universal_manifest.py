"""Canonical bounded manifest headers for the Universal Mixin dispatcher."""
import re

from artifact_checks import VerificationError


def mixin_manifest(configs):
    if not isinstance(configs, (tuple, list)) or not 1 <= len(configs) <= 8 \
            or len(set(configs)) != len(configs) or any(not isinstance(name, str)
            or re.fullmatch(r"p2wlan\.dispatch(?:\.fabric)?(?:\.1201)?(?:\.client)?\.json", name) is None
            for name in configs):
        raise VerificationError("universal_manifest_configs")
    lines = [b"Manifest-Version: 1.0"]
    value = ("MixinConfigs: " + ",".join(configs)).encode("ascii")
    lines.append(value[:72])
    value = value[72:]
    while value:
        lines.append(b" " + value[:71])
        value = value[71:]
    return b"\r\n".join(lines) + b"\r\n\r\n"
