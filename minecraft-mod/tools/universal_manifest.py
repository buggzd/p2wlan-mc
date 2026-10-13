"""Canonical bounded manifest headers for the Universal Mixin dispatcher."""
from artifact_checks import VerificationError
from minecraft_profiles import PROFILES


def mixin_manifest(configs):
    allowed = {name for profile in PROFILES.values()
               for name in (profile.binding.server_config, profile.binding.client_config)}
    if not isinstance(configs, (tuple, list)) or not 1 <= len(configs) <= 32 \
            or len(set(configs)) != len(configs) or any(not isinstance(name, str)
            or name not in allowed
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
