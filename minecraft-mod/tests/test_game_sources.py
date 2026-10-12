"""Exact one-source adaptation; tracked gameplay code remains the authority."""
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import game_sources as game
from source_contract import ContractError


class GameSourcesTests(unittest.TestCase):
    def source(self):
        return (game.PROJECT / 'java/game-1.21.1/src/client/java' / game.RELATIVE).read_text()

    def test_adaptation_changes_exactly_one_constructor_and_keeps_behavior(self):
        source = self.source()
        value = game.adapt(source)
        self.assertEqual(value.replace(game.AFTER, game.BEFORE), source)
        self.assertNotIn(game.BEFORE, value)
        self.assertEqual(value.count(game.AFTER), 1)
        for changed in [source.replace(game.BEFORE, 'changed constructor'), source + '\n' + game.BEFORE,
                        source.replace('ObjectSelectionList<ServerSelectionList.Entry>', 'UnownedList')]:
            with self.assertRaises(ContractError):
                game.adapt(changed)

    def test_only_one_ignored_generated_file_and_source_stays_unchanged(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'java/game-1.21.1/src/client/java' / game.RELATIVE
            source.parent.mkdir(parents=True)
            source.write_text(self.source())
            before = source.read_bytes()
            with patch.object(game, 'PROJECT', root):
                output = root / 'java/forge-legacy/build/generated/sources'
                target = game.prepare(output)
                self.assertEqual([p.relative_to(output) for p in output.rglob('*.java')], [game.RELATIVE])
                self.assertEqual(target.read_text(), game.adapt(before.decode()))
                self.assertEqual(game.prepare(output), target)
                self.assertEqual(source.read_bytes(), before)
                for unsafe in [Path('relative/build'), root / 'java/tracked', root / 'build/../tracked']:
                    with self.assertRaisesRegex(ValueError, 'LEGACY_GAME_OUTPUT_NOT_GENERATED'):
                        game.prepare(unsafe)

    def test_generated_directory_links_and_changed_source_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'java/game-1.21.1/src/client/java' / game.RELATIVE
            source.parent.mkdir(parents=True); source.write_text(self.source())
            output = root / 'build/generated'; output.mkdir(parents=True)
            elsewhere = root / 'outside'; elsewhere.mkdir()
            (output / 'io').symlink_to(elsewhere, target_is_directory=True)
            with patch.object(game, 'PROJECT', root):
                with self.assertRaisesRegex(ValueError, 'LEGACY_GAME_OUTPUT_LINK'):
                    game.prepare(output)
                self.assertEqual(list(elsewhere.iterdir()), [])
                (output / 'io').unlink()
                source.write_text('upstream changed')
                with self.assertRaises(ContractError):
                    game.prepare(output)
                self.assertEqual(list(output.iterdir()), [])


if __name__ == '__main__':
    unittest.main()
