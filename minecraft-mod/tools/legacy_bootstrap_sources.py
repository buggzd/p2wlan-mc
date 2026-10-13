"""Generate only fixed local owner/resource facts for the early Forge36 SPI."""
import argparse
import json
from pathlib import Path

from legacy_core_index import OWNERS, resource_names
from universal_jna import ISOLATED_PREFIX, isolated_resources


def java_source():
    quoted = lambda value: json.dumps(value, ensure_ascii=True)
    pairs = ', '.join('Map.entry(' + quoted(key) + ', ' + quoted(path) + ')' for key, path in OWNERS.items())
    hashes = ', '.join('Map.entry(' + quoted(key) + ', ' + quoted(value) + ')' for key, value in sorted(isolated_resources().items()))
    return '\n'.join(['package io.p2wlan.mc.legacybootstrap;', 'import java.util.Map;', 'import java.util.Set;',
        'final class LegacyInventory {',
        '    static final Map<String,String> OWNERS = Map.ofEntries(' + pairs + ');',
        '    static final Set<String> RESOURCES = Set.of(' + ', '.join(quoted(value) for value in sorted(resource_names())) + ');',
        '    static final String JNA_PREFIX = ' + quoted(ISOLATED_PREFIX) + ';',
        '    static final Map<String,String> JNA_SHA256 = Map.ofEntries(' + hashes + ');',
        '    private LegacyInventory() {}', '}', ''])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    output = parser.parse_args().output / 'io/p2wlan/mc/legacybootstrap/LegacyInventory.java'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(java_source(), encoding='utf-8')


if __name__ == '__main__':
    main()
