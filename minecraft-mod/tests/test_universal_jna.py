import hashlib
import io
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from artifact_checks import VerificationError
import universal_jna as jna


def archive(files):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as target:
        for name, value in files.items(): target.writestr(name, value)
    return output.getvalue()


def minimal_class(prefix=b'/com/sun/jna/'):
    # A real constant-pool layout with one String reference to the exact UTF-8.
    return b'\xca\xfe\xba\xbe\0\0\0\x34\0\x03\x01' + len(prefix).to_bytes(2, 'big') + prefix + b'\x08\0\x01' + bytes(14)


class UniversalJnaTests(unittest.TestCase):
    def setUp(self):
        self.original = minimal_class()
        self.files = {jna.NATIVE: self.original, **{name: name.encode() for name in jna.JNI_SHA256},
                      **{name: name.encode() for name in jna.METADATA_SHA256}}
        self.binding = patch.multiple(jna, ORIGINAL_NATIVE_SHA256=hashlib.sha256(self.original).hexdigest(),
            ORIGINAL_CLASS_COUNT=1, ORIGINAL_CLASS_SHA256=jna.class_digest({jna.NATIVE: self.original}),
            JNI_SHA256={name: hashlib.sha256(value).hexdigest() for name, value in self.files.items() if name in jna.JNI_SHA256},
            METADATA_SHA256={name: hashlib.sha256(value).hexdigest() for name, value in self.files.items() if name in jna.METADATA_SHA256})
        self.binding.start(); self.addCleanup(self.binding.stop)

    def test_single_reversible_constant_and_complete_unchanged_jni(self):
        result = jna.isolate(archive(self.files))
        hashes = jna.verify(result)
        self.assertEqual(len(hashes), 26)
        self.assertEqual(hashes, jna.isolated_resources())
        with zipfile.ZipFile(io.BytesIO(result)) as target:
            native = target.read(jna.NATIVE)
            self.assertEqual(jna._rewrite(native,b'/p2wlan/jna/',b'/com/sun/jna/'),self.original)
            for name, value in self.files.items():
                path = jna.ISOLATED_PREFIX + name.removeprefix(jna.ORIGINAL_PREFIX) if name in jna.JNI_SHA256 else name
                if name != jna.NATIVE: self.assertEqual(target.read(path), value)
                if name in jna.JNI_SHA256: self.assertNotIn(name,target.namelist())
        self.assertEqual(result,jna.isolate(archive(dict(reversed(list(self.files.items()))))))

    def test_rejects_repatch_and_raw_output_verification(self):
        with self.assertRaises(VerificationError): jna.isolate(jna.isolate(archive(self.files)))
        with self.assertRaises(VerificationError): jna.verify(archive(self.files))

    def test_rejects_every_unknown_resource_or_class_and_unknown_directory(self):
        for name in ('foreign/', 'com/sun/jna/Unknown.class', 'p2wlan/jna/custom/jnidispatch.dll',
                     'com/sun/jna/win32-x86-64/other.dll', 'META-INF/services/other.Service'):
            with self.subTest(name=name), self.assertRaises(VerificationError):
                jna.isolate(archive(self.files | {name: b'' if name.endswith('/') else b'changed'}))

    def test_every_jni_member_is_required_and_byte_bound(self):
        for name in jna.JNI_SHA256:
            missing = self.files.copy(); missing.pop(name)
            for value in (missing,self.files | {name: b'altered'}):
                with self.subTest(name=name), self.assertRaises(VerificationError): jna.isolate(archive(value))

    def test_native_and_other_class_bytes_are_bound_before_effects(self):
        for files in (self.files | {jna.NATIVE: self.original + b'changed'},
                      self.files | {'com/sun/jna/Pointer.class': self.original}):
            with self.assertRaises(VerificationError): jna.isolate(archive(files))

    def test_rejects_duplicate_zip_names_and_oversized_archive(self):
        output = io.BytesIO()
        with zipfile.ZipFile(output,'w') as target:
            target.writestr(jna.NATIVE,self.original)
            with self.assertWarns(UserWarning): target.writestr(jna.NATIVE,self.original)
        with self.assertRaises(VerificationError): jna.isolate(output.getvalue())
        with patch.object(jna,'MAX_ARCHIVE',10), self.assertRaises(VerificationError): jna.isolate(archive(self.files))

    def test_constant_pool_preserves_modified_utf8_and_requires_one_string_reference(self):
        prefix = b'/com/sun/jna/'; extra = b'\xc0\x80\xed\xa0\x80'
        value = minimal_class()[:8] + b'\0\x04' + minimal_class()[10:-14] + b'\x01' + len(extra).to_bytes(2,'big') + extra + bytes(14)
        altered = jna._rewrite(value,prefix,b'/p2wlan/jna/')
        self.assertIn(extra,altered)
        self.assertEqual(jna._rewrite(altered,b'/p2wlan/jna/',prefix),value)
        for value in (minimal_class(b'/wrong/'), minimal_class()[:-15], minimal_class().replace(b'\x08\0\x01',b'\x07\0\x01')):
            with self.assertRaises(VerificationError): jna._rewrite(value,prefix,b'/p2wlan/jna/')


if __name__ == '__main__': unittest.main()
