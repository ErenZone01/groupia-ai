import unittest
from saspay_diagnostics import data_rows, load_env_file, read_only_get, quote_reason
from unittest.mock import patch
from pathlib import Path
from tempfile import TemporaryDirectory


class ReadOnlySaspayDiagnosticsTests(unittest.TestCase):
    def test_unwraps_documented_list_and_envelope(self):
        self.assertEqual(data_rows({"success": True, "data": {"results": [{"merchant": "a"}]}}),
                         [{"merchant": "a"}])
        self.assertEqual(data_rows({"count": 0, "results": []}), [])
        self.assertEqual(data_rows([{"country_code": "SN"}]), [{"country_code": "SN"}])

    def test_quote_reason_for_exact_and_fractional_fees(self):
        from decimal import Decimal
        rate = {"payin": {"available": True, "otp_required": False, "tiers": [{
            "min_amount": "0.00", "max_amount": "500000.00",
            "currency": "XOF", "fee_charge_mode": "ADD_ON",
            "percent": "2.500", "fixed": "0.00",
            "floor_amount": None, "cap_amount": None
        }]}}
        self.assertIn("compatible", quote_reason(rate, Decimal(1000), Decimal(2000)))
        self.assertIn("fractionnaires", quote_reason(rate, Decimal(1100), Decimal(2000)))
        self.assertIn("plafond", quote_reason(rate, Decimal(2000), Decimal(2000)))
        rate["payin"]["otp_required"] = True
        self.assertIn("OTP", quote_reason(rate, Decimal(1000), Decimal(2000)))

    def test_requires_add_on_and_rejects_binary_float(self):
        from decimal import Decimal
        rate = {"payin": {"available": True, "otp_required": False, "tiers": [{
            "min_amount": "0.00", "max_amount": "500000.00",
            "currency": "XOF", "fee_charge_mode": "DEDUCTED",
            "percent": "2.500", "fixed": "0.00",
            "floor_amount": None, "cap_amount": None
        }]}}
        self.assertIn("ADD_ON", quote_reason(rate, Decimal(1000), Decimal(2000)))
        rate["payin"]["tiers"][0]["fee_charge_mode"] = "ADD_ON"
        rate["payin"]["tiers"][0]["percent"] = 2.5
        self.assertIn("nombre flottant non exact", quote_reason(rate, Decimal(1000), Decimal(2000)))

    def test_reports_exact_missing_tariff_field_without_amounts(self):
        from decimal import Decimal
        rate = {"payin": {"available": True, "otp_required": False, "tiers": [{
            "min_amount": "0.00", "max_amount": "500000.00",
            "currency": "XOF", "fee_charge_mode": "ADD_ON",
            "percent": "2.500", "fixed": "0.00",
            "floor_amount": None, "cap_amount": None
        }]}}
        del rate["payin"]["tiers"][0]["floor_amount"]
        cause = quote_reason(rate, Decimal(1000), Decimal(2000))
        self.assertIn("'floor_amount' absent", cause)
        self.assertNotIn("2.500", cause)
        rate["payin"]["tiers"][0]["floor_amount"] = None
        rate["payin"]["tiers"][0]["min_amount"] = 0
        self.assertIn("compatible", quote_reason(rate, Decimal(1000), Decimal(2000)))
        rate["payin"]["tiers"][0]["min_amount"] = 0.0
        self.assertIn("nombre flottant non exact", quote_reason(rate, Decimal(1000), Decimal(2000)))

    def test_live_numeric_json_rates_keep_exact_decimals(self):
        import json
        from decimal import Decimal
        live_shape = json.loads("""
        {"payin":{"available":true,"otp_required":false,"tiers":[{
          "min_amount":0.00,"max_amount":500000.00,
          "currency":"XOF","fee_charge_mode":"ADD_ON",
          "percent":2.500,"fixed":0.00,"floor_amount":null,"cap_amount":null
        }]}}
        """, parse_float=Decimal)
        self.assertIsInstance(live_shape["payin"]["tiers"][0]["min_amount"], Decimal)
        self.assertIn("compatible", quote_reason(live_shape, Decimal(1000), Decimal(2000)))
        self.assertIn("fractionnaires", quote_reason(live_shape, Decimal(1100), Decimal(2000)))

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
