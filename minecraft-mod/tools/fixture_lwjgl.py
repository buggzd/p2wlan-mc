"""Pinned complete LWJGL family for older macOS ARM64 gameplay fixtures."""

VERSION = '3.3.2'
CLASSIFIER = 'natives-macos-arm64'
# Official Maven artifacts, checked against their published SHA1 sidecars.
# Each pair is the Java binding and the matching macOS ARM64 native artifact.
_ARTIFACTS = {
    'lwjgl': ((786196, 'f8016606e74f3cafd63eae07b1331f903f999c9f40726c838399a144c3809217'),
              (48270, 'ebb8474ad3c19b906649ac41cef62314fd9b355cea5509d9be0c8728db7a6c38')),
    'lwjgl-glfw': ((135828, '29f2fff9dfa7247bde7abc20c3e074d2384dbdd64ffbea172c86a392000f8b48'),
                   (137535, '6ea0409313d5a75bf12ffd2872246fbd34701b0b7760e5f81e09645d023ead89')),
    'lwjgl-jemalloc': ((43233, '9e1a6a76b2c08fc72c2e893151930b2018cafd5a23d8242187c72d181cb587b0'),
                       (142101, '83e93e7bff2986537d0737a1413504776104a8449fe9a3f560fb62ccc628288c')),
    'lwjgl-openal': ((108230, '2a58483b99c882a332d086402fccb8272664327016dc5a261a98d87f19d0f562'),
                     (467151, 'b8adeffe3d7fe965d11ca24da8c7e828ed15e997bb41450857a5379301b1b784')),
    'lwjgl-opengl': ((928871, '0ebaaf92d306c946ce9d3db25496b9068b7aab782b15966be2544b6a24838038'),
                     (42490, '3120ecbcccf31f36064f260fa0127b4a359097a294ac5b3e7675d1867168f2e6')),
    'lwjgl-stb': ((118874, '48ad3b167188dc29482ae51a318b3b486e0db7b1c414fc4f09856789220f56b6'),
                  (183630, '998a5142f97fc60c799ea59616bddafd047c0e7f7ac1a2aadb31abbdbcadcfb8')),
    'lwjgl-tinyfd': ((13404, '0fd2aa644f3b937cc38102fe1fde8103f5aa6ba00de587cec378f92f50101cfa'),
                     (41812, 'bf1b8dfedde0bfd56dbd85dae6f66f340717bed56b2399c90c83d4857ecfba1f')),
}


def replacement(minecraft, platform):
    if minecraft not in ('1.16.5', '1.18.2') or tuple(platform) != ('osx', 'arm64'):
        return {}
    result = {}
    for module, variants in _ARTIFACTS.items():
        for classifier, (size, sha256) in zip(('', CLASSIFIER), variants):
            name = f'{module}-{VERSION}' + ('-' + classifier if classifier else '') + '.jar'
            path = f'org/lwjgl/{module}/{VERSION}/{name}'
            coordinate = f'org.lwjgl:{module}:{VERSION}' + (':' + classifier if classifier else '')
            result[coordinate] = {'path': path, 'size': size, 'sha256': sha256,
                                  'url': 'https://repo.maven.apache.org/maven2/' + path}
    return result


def libraries(minecraft, platform, selected):
    changed = replacement(minecraft, platform)
    if not changed:
        return dict(selected)
    original = {name for name in selected if name.startswith('org.lwjgl:')}
    if {name.split(':')[1] for name in original} != set(_ARTIFACTS) \
            or any(len(name.split(':')) not in (3, 4) or name.split(':')[2] not in ('3.2.1', '3.2.2') for name in original):
        raise ValueError('GAME_FIXTURE_LWJGL_SOURCE_CONTRACT')
    return {name: record for name, record in selected.items() if name not in original} | changed


def identity(minecraft, platform):
    changed = replacement(minecraft, platform)
    if not changed:
        return None
    return {'version': VERSION, 'classifier': CLASSIFIER,
            'artifacts': [{'coordinate': name, 'path': record['path'], 'size': record['size'],
                           'sha256': record['sha256']} for name, record in sorted(changed.items())]}
