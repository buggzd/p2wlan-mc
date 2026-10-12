from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from universal_manifest import mixin_manifest
from artifact_checks import VerificationError


class UniversalManifestTests(unittest.TestCase):
    def test_long_version_dispatch_header_is_folded_without_changing_config_names(self):
        configs = ["p2wlan.dispatch.json", "p2wlan.dispatch.client.json",
                   "p2wlan.dispatch.1201.json", "p2wlan.dispatch.1201.client.json"]
        value = mixin_manifest(configs)
        self.assertTrue(value.endswith(b"\r\n\r\n"))
        self.assertTrue(all(len(line) <= 72 for line in value.split(b"\r\n")))
        self.assertIn(b"\r\n ", value)
        unfolded = value.replace(b"\r\n ", b"")
        self.assertEqual(unfolded, ("Manifest-Version: 1.0\r\nMixinConfigs: " + ",".join(configs) + "\r\n\r\n").encode())

    def test_config_names_are_bounded_unique_and_cannot_inject_headers(self):
        for configs in ([], ["p2wlan.dispatch.json"] * 2, ["../config.json"], ["p2wlan.dispatch.json\r\nOther: value"], [42]):
            with self.assertRaises(VerificationError):
                mixin_manifest(configs)


if __name__ == "__main__":
    unittest.main()
