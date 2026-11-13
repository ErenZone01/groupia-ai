import unittest
from saspay_diagnostics import data_rows, load_env_file, read_only_get, quote_reason, print_quote_diagnostic, compatible_amounts, diagnose_webhooks
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

    def test_diagnostic_reports_missing_display_name_without_exposing_prices(self):
        from decimal import Decimal
        from contextlib import redirect_stdout
        from io import StringIO
        rate = {"country_code": "SN", "currency": "XOF", "network_code": "wizall_sn",
                "payin": {"available": True, "otp_required": False, "tiers": [{
                    "min_amount": 0, "max_amount": 500000, "currency": "XOF",
                    "fee_charge_mode": "ADD_ON", "percent": Decimal("2.500"),
                    "fixed": 0, "floor_amount": None, "cap_amount": None,
                }]}}
        buf = StringIO()
        with redirect_stdout(buf):
            print_quote_diagnostic([rate], Decimal(1000), Decimal(2000))
        self.assertIn("network_name absent", buf.getvalue())
        self.assertNotIn("2.500", buf.getvalue())
        rate["network_name"] = "Wizall"
        buf = StringIO()
        with redirect_stdout(buf):
            print_quote_diagnostic([rate], Decimal(1000), Decimal(2000))
        self.assertIn("compatible", buf.getvalue())

    def test_numeric_exponent_notation_is_exact_and_bounded(self):
        from decimal import Decimal
        from saspay_diagnostics import _money
        self.assertEqual(_money(Decimal("0E-12")), Decimal(0))
        self.assertEqual(_money(Decimal("1E+3")), Decimal(1000))
        self.assertEqual(_money(Decimal("2.500")), Decimal("2.500"))
        for value in (Decimal("0.0000001"), Decimal("-1"), Decimal("1E+14"), "2.5E+0"):
            with self.subTest(value=str(value)), self.assertRaises(ValueError):
                _money(value)

    def test_suggests_only_integer_fee_amounts_under_pilot_cap(self):
        from decimal import Decimal
        tier = {
            "min_amount": Decimal("200.00"), "max_amount": Decimal("500000.00"),
            "currency": "XOF", "fee_charge_mode": "ADD_ON",
            "percent": Decimal("2.250"), "fixed": Decimal("100.00"),
            "floor_amount": None, "cap_amount": None,
        }
        rate = {
            "country_code": "SN", "currency": "XOF",
            "network_code": "wave_sn", "network_name": "Wave",
            "payin": {"available": True, "otp_required": False, "tiers": [tier]},
        }
        suggestions = compatible_amounts(rate, Decimal(1000), Decimal(2000))
        self.assertEqual(suggestions, [800, 1200, 400])
        for amount in suggestions:
            self.assertTrue(quote_reason(rate, Decimal(amount), Decimal(2000))
                            .startswith("devis théoriquement compatible"))
        rate["network_name"] = ""
        self.assertEqual(compatible_amounts(rate, Decimal(1000), Decimal(2000)), [])

    def test_no_suggestion_when_fee_does_not_fit_pilot_ceiling(self):
        from decimal import Decimal
        rate = {
            "country_code": "SN", "currency": "XOF",
            "network_code": "djamo_sn", "network_name": "Djamo",
            "payin": {"available": True, "otp_required": False, "tiers": [{
                "min_amount": Decimal("2000"), "max_amount": None,
                "currency": "XOF", "fee_charge_mode": "ADD_ON",
                "percent": Decimal("1.000"), "fixed": Decimal("200"),
                "floor_amount": None, "cap_amount": None,
            }]},
        }
        self.assertEqual(compatible_amounts(rate, Decimal(1000), Decimal(2000)), [])

    def test_webhook_configuration_read_only_and_secret_redaction(self):
        from contextlib import redirect_stdout
        from io import StringIO
        webhooks = [{
            "id": "synthetic-webhook-id", "url":
                "https://groupia.example/api/payment/webhooks/saspay?private=hidden",
            "environment": "LIVE", "is_active": True
        }]
        subscriptions = [{"webhook": "synthetic-webhook-id",
                          "event_type": "transaction.success"}]
        with patch("saspay_diagnostics.read_only_get",
                   side_effect=[webhooks, subscriptions]) as request:
            output = StringIO()
            with redirect_stdout(output):
                diagnose_webhooks({"SASPAY_WEBHOOK_SECRET": "very-private"}, "sk_test_fake")
        text = output.getvalue()
        self.assertEqual(request.call_count, 2)
        self.assertIn("chemin Groupia attendu=OUI", text)
        self.assertIn("transaction.success", text)
        self.assertNotIn("very-private", text)
        self.assertNotIn("private=hidden", text)

    def test_opted_in_fractional_xof_fees_match_exact_java_pilot_policy(self):
        from decimal import Decimal
        rate = {
            "country_code": "SN", "currency": "XOF", "network_code": "wave_sn",
            "network_name": "Wave", "payin": {
                "available": True, "otp_required": False, "tiers": [{
                    "min_amount": Decimal("200"), "max_amount": Decimal("500000"),
                    "currency": "XOF", "fee_charge_mode": "ADD_ON",
                    "percent": Decimal("2.25"), "fixed": Decimal("0"),
                    "floor_amount": None, "cap_amount": None,
                }],
            }
        }
        self.assertIn("option pilote non activée",
                      quote_reason(rate, Decimal("1000"), Decimal("2000")))
        self.assertTrue(
            quote_reason(rate, Decimal("1000"), Decimal("2000"), True)
            .startswith("devis théoriquement compatible")
        )
        self.assertIn("supérieure à deux décimales",
                      quote_reason(rate, Decimal("1001"), Decimal("2000"), True))
        self.assertIn(
            1000,
            compatible_amounts(
                rate, Decimal("1000"), Decimal("2000"), allow_fractional=True
            ),
        )

    def test_payout_diagnostic_requires_deducted_and_positive_net(self):
        from decimal import Decimal
        from saspay_diagnostics import payout_quote_reason
        tier = {
            "min_amount": "0.00", "max_amount": "500000.00",
            "currency": "XOF", "fee_charge_mode": "DEDUCTED",
            "percent": "2.0", "fixed": "10.00",
            "floor_amount": None, "cap_amount": None,
        }
        rate = {"country_code": "SN", "currency": "XOF",
                "payout": {"available": True, "otp_required": False, "tiers": [tier]}}
        self.assertEqual(payout_quote_reason(rate, Decimal(1000)),
                         ("DEDUCTED compatible à titre indicatif", Decimal(30)))
        tier["fee_charge_mode"] = "ADD_ON"
        self.assertIn("DEDUCTED non confirmé", payout_quote_reason(rate, Decimal(1000))[0])
        tier["fee_charge_mode"] = "DEDUCTED"
        tier["percent"] = "2.25"
        self.assertEqual(payout_quote_reason(rate, Decimal(1020))[0], "DEDUCTED compatible à titre indicatif")
        rate["payout"]["available"] = False
        self.assertIn("non disponible", payout_quote_reason(rate, Decimal(1000))[0])

    def test_payout_diagnostic_reports_only_authorized_configured_network_codes(self):
        from decimal import Decimal
        from contextlib import redirect_stdout
        from io import StringIO
        from saspay_diagnostics import print_payout_diagnostic
        rates = [{
            "country_code": "SN", "currency": "XOF", "network_code": "wave_sn",
            "payout": {"available": True, "otp_required": False, "tiers": [{
                "min_amount": "0", "max_amount": None,
                "currency": "XOF", "fee_charge_mode": "DEDUCTED",
                "percent": "2", "fixed": "0",
                "floor_amount": None, "cap_amount": None,
            }]},
        }]
        env = {"SASPAY_PAYOUTS_WAVE_NETWORK": "wave_sn",
               "SASPAY_PAYOUTS_ORANGE_NETWORK": ""}
        output = StringIO()
        with redirect_stdout(output):
            print_payout_diagnostic(rates, env, Decimal(1000))
        text = output.getvalue()
        self.assertIn("DEDUCTED compatible", text)
        self.assertIn("net vendeur 980 XOF", text)
        self.assertIn("Orange Money: code réseau payout non configuré", text)
        self.assertIn("net final reste à rapprocher", text.lower())

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
