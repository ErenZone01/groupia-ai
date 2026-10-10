import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import check_migrations as guard


class MigrationGuardTests(unittest.TestCase):
    def test_repository(self):
        self.assertEqual(22, guard.verify())

    def test_byte_change_is_rejected(self):
        original = Path.read_bytes
        def changed(path):
            data = original(path)
            return data + b" " if path.name.startswith("002-") else data
        with patch.object(Path, "read_bytes", changed):
            with self.assertRaisesRegex(ValueError, "working migration changed"):
                guard.verify()

    def test_manifest_cannot_rebaseline_changed_file(self):
        original = json.loads
        def changed(data):
            result = original(data)
            result[next(iter(result))] = "0" * 64
            return result
        with patch.object(json, "loads", changed):
            with self.assertRaisesRegex(ValueError, "manifest/committed"):
                guard.verify()

    def test_missing_migration_is_rejected(self):
        original = Path.read_bytes
        def missing(path):
            if path.name.startswith("002-"):
                raise FileNotFoundError(path)
            return original(path)
        with patch.object(Path, "read_bytes", missing):
            with self.assertRaises(FileNotFoundError):
                guard.verify()


if __name__ == "__main__":
    unittest.main()
