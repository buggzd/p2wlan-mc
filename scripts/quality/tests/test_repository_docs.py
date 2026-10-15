import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


ENTRY = Path(__file__).resolve().parents[2] / "docs/verify_repository.py"
SPEC = importlib.util.spec_from_file_location("repository_docs", ENTRY)
docs = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(docs)


class RepositoryDocsTests(unittest.TestCase):
    def test_document_inventory_includes_mod_and_upstream(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paths = ("docs/reference/networking.md", "minecraft-mod/docs/reference/building.md")
            for name in (*paths, "docs/generated/export.md", "docs/adr/local.md"):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("# Reference\n", encoding="utf-8")
            with patch.object(docs, "DOCS", root / "docs"), patch.object(docs, "MOD", root / "minecraft-mod"):
                self.assertEqual(docs.markdown_files(), sorted(root / name for name in paths))

    def test_mod_links_are_relative_to_their_document(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            guide = root / "minecraft-mod/docs/quickstart.md"
            guide.parent.mkdir(parents=True)
            readme = guide.parent.parent / "README.md"
            readme.write_text("# Mod\n", encoding="utf-8")
            errors = []
            with patch.object(docs, "ROOT", root):
                docs.check_links(guide, "[Mod](../README.md) [build](reference/missing.md)", errors)
            self.assertEqual(errors, ["minecraft-mod/docs/quickstart.md: broken link reference/missing.md"])

    def test_mod_product_and_reference_documents_are_required(self):
        required = {path.relative_to(docs.ROOT).as_posix() for path in docs.REQUIRED}
        self.assertTrue({
            "minecraft-mod/README.md", "minecraft-mod/docs/quickstart.md",
            "minecraft-mod/docs/reference/building.md", "minecraft-mod/docs/reference/testing.md",
            "docs/guides/self-hosting.md",
        }.issubset(required))


if __name__ == "__main__":
    unittest.main()
