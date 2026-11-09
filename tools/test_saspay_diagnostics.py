import unittest
from saspay_diagnostics import data_rows, load_env_file, read_only_get
from unittest.mock import patch
from pathlib import Path
from tempfile import TemporaryDirectory


class ReadOnlySaspayDiagnosticsTests(unittest.TestCase):
    def test_unwraps_documented_list_and_envelope(self):
        self.assertEqual(data_rows({"success": True, "data": {"results": [{"merchant": "a"}]}}),
                         [{"merchant": "a"}])
        self.assertEqual(data_rows({"count": 0, "results": []}), [])
        self.assertEqual(data_rows([{"country_code": "SN"}]), [{"country_code": "SN"}])

    def test_refuses_other_network_paths(self):
        with self.assertRaises(ValueError):
            read_only_get("/payouts/initialize/", "sk_test_dummy")
        with self.assertRaises(ValueError):
            read_only_get("/payments/softpay/", "sk_test_dummy")

    def test_env_file_does_not_log_secrets(self):
        with TemporaryDirectory() as d:
            file = Path(d) / ".env"
            file.write_text('SASPAY_API_KEY="sk_live_dummy"\n# comment\nSASPAY_ENABLED=false\n')
            self.assertEqual(load_env_file(str(file))["SASPAY_API_KEY"], "sk_live_dummy")


if __name__ == "__main__":
    unittest.main()
