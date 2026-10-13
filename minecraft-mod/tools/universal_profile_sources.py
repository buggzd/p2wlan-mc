#!/usr/bin/env python3
"""Generate loader-neutral bootstrap facts from the exact profile registry."""
import argparse
import json
from pathlib import Path

from minecraft_profiles import DEFAULT_MINECRAFT, PROFILES, forge_range, fml_range, game_range


def bindings():
    result = {}
    for item in PROFILES.values():
        key = (item.family, item.minecraft)
        previous = result.setdefault(key, item.binding)
        if previous != item.binding:
            raise ValueError('UNIVERSAL_PROFILE_BINDING_CONFLICT')
    return tuple(result.values())


def dispatch_configs():
    result = {}
    for item in bindings():
        for client, filename in ((False, item.server_config), (True, item.client_config)):
            value = {'required': True, 'minVersion': '0.8.4',
                     'compatibilityLevel': 'JAVA_17', 'mixins': [],
                     'injectors': {'defaultRequire': 1},
                     'package': item.game_namespace + ('.mixin' if client else '.server.mixin'),
                     'plugin': 'io.p2wlan.mc.bootstrap.' + ('ClientMixinPlugin' if client else 'ServerMixinPlugin')}
            if filename in result:
                raise ValueError('UNIVERSAL_PROFILE_CONFIG_CONFLICT')
            result[filename] = value
    return result


def literal(value):
    return 'null' if value is None else json.dumps(value, ensure_ascii=True)


def java_list(values):
    return 'List.of(' + ', '.join(map(literal, values)) + ')'


def java_source():
    pairs = tuple(PROFILES.values())
    selected = bindings()
    lines = ['package io.p2wlan.mc.bootstrap;', '',
             'import java.util.List;', 'import java.util.Set;',
             'import static io.p2wlan.mc.bootstrap.UniversalEnvironment.*;',
             'import static io.p2wlan.mc.bootstrap.BootstrapFailure.Code;', '',
             '/** Generated from minecraft_profiles.py; contains no game or loader API. */',
             'final class UniversalProfiles {',
             '    static final String DEFAULT_VERSION = ' + literal(DEFAULT_MINECRAFT) + ';',
             '    static final String FORGE_RANGE = ' + literal(forge_range()) + ';',
             '    static final String FML_RANGE = ' + literal(fml_range()) + ';',
             '    static final String GAME_RANGE = ' + literal(game_range()) + ';',
             '    private static final Set<String> INSTALLATIONS = Set.of(' +
             ', '.join(literal(item.loader.upper() + "|" + item.minecraft) for item in pairs) + ');',
             '    private static final Set<String> BINDINGS = Set.of(' +
             ', '.join(literal(item.family.upper() + "|" + item.minecraft) for item in selected) + ');',
             '    private UniversalProfiles() {}',
             '    static boolean supports(Loader loader, String version) {',
             '        return version != null && INSTALLATIONS.contains(loader.name() + "|" + version);',
             '    }',
             '    static boolean supports(Family family, String version) {',
             '        return version != null && BINDINGS.contains(family.name() + "|" + version);',
             '    }',
             '    static Binding binding(String namespace) {',
             '        return switch (namespace) {']
    for item in selected:
        lines.append('            case ' + literal(item.game_namespace) + ' -> new Binding(Family.' +
                     item.family.upper() + ', ' + literal(item.minecraft) + ');')
    lines += ['            default -> throw new BootstrapFailure(Code.MIXIN_PACKAGE_INVALID);',
              '        };', '    }',
              '    static List<String> mixins(Binding binding, boolean client) {',
              '        return switch (binding.family().name() + "|" + binding.minecraftVersion()) {']
    for item in selected:
        lines.append('            case ' + literal(item.family.upper() + '|' + item.minecraft) +
                     ' -> client ? ' + java_list(item.mixins.client) + ' : ' + java_list(item.mixins.server) + ';')
    lines += ['            default -> throw new BootstrapFailure(Code.MIXIN_PACKAGE_INVALID);',
              '        };', '    }',
              '    static String refmap(Binding binding, boolean client) {',
              '        return switch (binding.family().name() + "|" + binding.minecraftVersion()) {']
    for item in selected:
        lines.append('            case ' + literal(item.family.upper() + '|' + item.minecraft) +
                     ' -> client ? ' + literal(item.mixins.client_refmap) + ' : ' + literal(item.mixins.server_refmap) + ';')
    lines += ['            default -> throw new BootstrapFailure(Code.MIXIN_PACKAGE_INVALID);',
              '        };', '    }', '}', '']
    return '\n'.join(lines)


def generate(output):
    source = output / 'java/io/p2wlan/mc/bootstrap/UniversalProfiles.java'
    source.parent.mkdir(parents=True, exist_ok=True)
    source.write_text(java_source(), encoding='utf-8')
    resources = output / 'resources'
    resources.mkdir(parents=True, exist_ok=True)
    configs = dispatch_configs()
    for stale in resources.glob('p2wlan.dispatch*.json'):
        if stale.name not in configs:
            stale.unlink()
    for name, value in configs.items():
        (resources / name).write_text(json.dumps(value, sort_keys=True, indent=2) + '\n', encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    generate(parser.parse_args().output)


if __name__ == '__main__':
    main()
