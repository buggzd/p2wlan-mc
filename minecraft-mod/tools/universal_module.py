"""Canonical Java-17 SERVICE descriptor, with no exported application packages."""
import struct

NAME = 'module-info.class'
MODULE = 'p2wlan.bootstrap'
PACKAGE = 'io/p2wlan/mc/legacybootstrap'
PROVIDES = ('net/minecraftforge/forgespi/locating/IModLocator',
            'io/p2wlan/mc/legacybootstrap/LegacyModLocator')
REQUIRES = (('java.base', 0x8000), ('cpw.mods.modlauncher', 0),
            ('net.minecraftforge.forgespi', 0), ('fmlloader', 0x40),
            ('net.minecraftforge.fmlloader', 0x40))


def descriptor():
    """Emit one fixed, nonautomatic module; GAME uses FML's ModJarMetadata.

    The SERVICE copy supplies only the locator SPI. It exports no package to
    automatic GAME modules. Optional FML read edges cover the pinned old and
    new SDK module names; the legacy URL-classpath runtime ignores this file.
    No caller-supplied name, version, export or dependency can widen the graph.
    """
    constants = []
    cache = {}

    def utf(value):
        key = (1, value)
        if key not in cache:
            raw = value.encode('ascii')
            constants.append(b'\x01' + struct.pack('>H', len(raw)) + raw)
            cache[key] = len(constants)
        return cache[key]

    def reference(tag, value):
        key = (tag, value)
        if key not in cache:
            index = utf(value)
            constants.append(bytes((tag,)) + struct.pack('>H', index))
            cache[key] = len(constants)
        return cache[key]

    this = reference(7, 'module-info')
    module_attribute = utf('Module')
    packages_attribute = utf('ModulePackages')
    module = reference(19, MODULE)
    requires = [(reference(19, name), flags) for name, flags in REQUIRES]
    service, provider = (reference(7, value) for value in PROVIDES)
    package = reference(20, PACKAGE)
    content = struct.pack('>4H', module, 0, 0, len(requires))
    content += b''.join(struct.pack('>3H', index, flags, 0) for index, flags in requires)
    content += struct.pack('>4H', 0, 0, 0, 1)
    content += struct.pack('>3H', service, 1, provider)
    packages = struct.pack('>2H', 1, package)
    header = b'\xca\xfe\xba\xbe' + struct.pack('>3H', 0, 61, len(constants) + 1)
    body = struct.pack('>7H', 0x8000, this, 0, 0, 0, 0, 2)
    attributes = struct.pack('>HI', module_attribute, len(content)) + content
    attributes += struct.pack('>HI', packages_attribute, len(packages)) + packages
    return header + b''.join(constants) + body + attributes
