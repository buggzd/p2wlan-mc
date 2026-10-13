"""Verify the exact standalone Forge locator carrier before Universal assembly."""
import argparse
import hashlib
from pathlib import Path
import zipfile

from artifact_checks import VerificationError, classfile, entries, read
from legacy_core_index import SERVICE, SERVICE_CLASS

PREFIX = 'io/p2wlan/mc/legacybootstrap/'
CLASSES = frozenset(PREFIX + name + '.class' for name in (
    'LegacyArchive', 'LegacyEligibility', 'LegacyEligibility$Binding', 'LegacyFailure', 'LegacyFailure$Code',
    'LegacyCache', 'LegacyCache$Reason', 'LegacyCache$Lease', 'LegacyCache$Observed', 'LegacyCache$Delete', 'LegacyCache$Snapshot',
    'LegacyFmlBridge', 'LegacyIndex', 'LegacyInventory', 'LegacyModLocator', 'LegacyOwner',
    'LegacyResourceVerifier', 'LegacyResourceVerifier$Provenance', 'LegacyResourceVerifier$Resource', 'LegacyResourceVerifier$Resources',
    'OwnerFile', 'VerifiedJar', 'VerifiedJar$1', 'VerifiedJar$VerifiedConnection', 'VerifiedJar$VerifiedConnection$1'))
SERVICE_BYTES = (SERVICE_CLASS + '\n').encode('ascii')
FORBIDDEN = (b'net/minecraft/', b'io/p2wlan/mc/common/', b'io/p2wlan/mc/transport/', b'com/sun/jna/Native',
             b'java/net/Socket', b'java/net/ServerSocket', b'java/net/HttpURLConnection', b'java/lang/ProcessBuilder',
             b'sun/misc/Unsafe', b'setAccessible', b'ITransformationService')


def verify(source):
    """Return carrier members; dependencies, application instances and scans stay external."""
    with zipfile.ZipFile(source) as archive:
        names = entries(archive)
        members = {name for name in names if not name.endswith('/')}
        if members != CLASSES | {SERVICE, 'META-INF/MANIFEST.MF'}:
            raise VerificationError('legacy_bootstrap_catalog')
        if read(archive, SERVICE, 256) != SERVICE_BYTES:
            raise VerificationError('legacy_bootstrap_service')
        for name in CLASSES:
            value = classfile(archive, name, 61)
            # Resource inventories contain package paths, but no application type descriptor.
            forbidden = FORBIDDEN if name not in {PREFIX + 'LegacyInventory.class', PREFIX + 'LegacyOwner.class',
                                                 PREFIX + 'LegacyResourceVerifier.class'} else FORBIDDEN[:1] + FORBIDDEN[4:]
            if any(marker in value for marker in forbidden):
                raise VerificationError('legacy_bootstrap_application_boundary')
        return {name: read(archive, name, 1024 * 1024) for name in CLASSES | {SERVICE}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    args = parser.parse_args()
    try:
        verify(args.archive)
    except (OSError, ValueError, zipfile.BadZipFile) as failure:
        parser.exit(1, (str(failure) if isinstance(failure, VerificationError) else 'legacy_bootstrap_archive_io') + '\n')
    print('PASS exact Forge locator carrier: Java 17, no bundled SDK or application owners')
    print(hashlib.sha256(args.archive.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
