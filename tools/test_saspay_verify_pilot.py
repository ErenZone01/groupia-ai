"""No-network contract tests for the read-only SasPay pilot verification CLI."""
import unittest
from decimal import Decimal
from saspay_verify_pilot import safe_summary, fetch_payment
from unittest.mock import patch


class SaspayPilotVerificationTests(unittest.TestCase):
    def setUp(self):
        self.payment = {
            "status": "FAILED",
            "merchant": "00000000-0000-0000-0000-000000000001",
            "transaction_type": "PAIEMENT",
            "flow_direction": "INBOUND",
            "requested_amount": "1000.00",
            "client_fee": "139.00",
            "debited_amount": "1139.00",
            "net_amount": "1000.00",
            "fee_charge_mode": "ADD_ON",
            "currency": "XOF",
        }
        self.merchant = "00000000-0000-0000-0000-000000000001"

    def check(self):
        return safe_summary(self.payment, self.merchant, Decimal("1000"), Decimal("2000"))

    def test_failed_transaction_never_confirms_even_with_valid_financials(self):
        result = self.check()
        self.assertEqual(result["status"], "FAILED")
        self.assertTrue(all(result["checks"].values()))
        self.assertFalse(result["may_confirm_order"])

    def test_only_verified_success_with_exact_financials_may_confirm(self):
        self.payment["status"] = "SUCCESS"
        self.assertTrue(self.check()["may_confirm_order"])
        self.payment["net_amount"] = "999.00"
        self.assertFalse(self.check()["may_confirm_order"])
        self.payment["net_amount"] = "1000.00"
        self.payment["client_fee"] = "140.00"
        self.assertFalse(self.check()["may_confirm_order"])
        self.payment["client_fee"] = "139.00"
        self.payment["merchant"] = "00000000-0000-0000-0000-000000000002"
        self.assertFalse(self.check()["may_confirm_order"])

    def test_pending_transaction_and_over_cap_must_not_confirm(self):
        self.payment["status"] = "PENDING"
        self.assertFalse(self.check()["may_confirm_order"])
        self.payment["status"] = "SUCCESS"
        self.payment["debited_amount"] = "2010"
        self.payment["client_fee"] = "1010"
        self.assertFalse(self.check()["may_confirm_order"])

    def test_bad_response_shapes_fail_closed(self):
        for value in ("0.0000001", "-1", True, None, [], {}):
            with self.subTest(value=value):
                self.payment["client_fee"] = value
                with self.assertRaises(ValueError):
                    self.check()

    def test_negative_provider_envelope_never_counts_as_success(self):
        self.payment["status"] = "SUCCESS"
        with self.assertRaises(ValueError):
            safe_summary(
                {"success": False, "data": self.payment},
                self.merchant, Decimal("1000"), Decimal("2000"))
        self.assertTrue(
            safe_summary(
                {"success": True, "data": self.payment},
                self.merchant, Decimal("1000"), Decimal("2000"))["may_confirm_order"]
        )

    def test_verify_404_diagnostic_never_confirms_or_mutates(self):
        from contextlib import redirect_stdout
        from io import StringIO
        from saspay_verify_pilot import diagnose_verify_404
        uid = "253c31ba-9b54-44f8-958e-a6d78d1bbe87"
        record = {
            "id": uid,
            "merchant": self.merchant,
            "status": "SUCCESS",
        }
        output = StringIO()
        with (
            patch("saspay_verify_pilot.fetch_transaction_detail", return_value=record),
            patch("saspay_verify_pilot.fetch_transaction_page",
                  return_value={"results": [record], "next": None}),
            redirect_stdout(output),
        ):
            code = diagnose_verify_404("sk_test_fake", uid, self.merchant)
        self.assertEqual(code, 1)
        self.assertIn("Statut indicatif : SUCCESS", output.getvalue())
        self.assertIn("ne remplace PAS", output.getvalue())
        self.assertIn("aucune confirmation", output.getvalue())

    def test_diagnostic_404_preserves_account_and_handles_missing_records(self):
        from contextlib import redirect_stdout
        from io import StringIO
        from saspay_verify_pilot import diagnose_verify_404
        from urllib.error import HTTPError
        uid = "d6b9b3d0-e4ed-4341-ae14-7d7f56909361"
        output = StringIO()
        with (
            patch("saspay_verify_pilot.fetch_transaction_detail",
                  side_effect=HTTPError("https://api.saspay.me", 404, "Not Found", {}, None)),
            patch("saspay_verify_pilot.fetch_transaction_page",
                  return_value={"results": [], "next": "next-page"}),
            redirect_stdout(output),
        ):
            code = diagnose_verify_404("sk_test_fake", uid, self.merchant)
        self.assertEqual(code, 1)
        self.assertIn("HTTP 404", output.getvalue())
        self.assertIn("non concluante", output.getvalue())
        self.assertNotIn("sk_test_fake", output.getvalue())

    def test_list_and_detail_probes_use_only_fixed_get_paths(self):
        import json
        from io import BytesIO
        from saspay_verify_pilot import fetch_transaction_detail, fetch_transaction_page
        uid = "253c31ba9b5444f8958ea6d78d1bbe87"
        class FakeResponse:
            def __init__(self, payload):
                self.data = BytesIO(json.dumps(payload).encode())
            def __enter__(self):
                return self
            def __exit__(self, *args):
                return None
            def read(self, n):
                return self.data.read(n)
        calls = []
        def respond(req, timeout):
            calls.append((req.get_method(), req.full_url))
            if "/transactions/?" in req.full_url:
                return FakeResponse({"results": [], "next": None})
            return FakeResponse({"id": "253c31ba-9b54-44f8-958e-a6d78d1bbe87"})
        with patch("saspay_verify_pilot.urlopen", side_effect=respond):
            fetch_transaction_detail("sk_test_fake", uid)
            fetch_transaction_page("sk_test_fake")
        self.assertEqual([m for m, _ in calls], ["GET", "GET"])
        self.assertTrue(calls[0][1].endswith(
            "/transactions/253c31ba-9b54-44f8-958e-a6d78d1bbe87/"))
        self.assertTrue(calls[1][1].startswith(
            "https://api.saspay.me/api/v1/transactions/?"))

    def test_scope_audit_is_read_only_and_never_reveals_customers(self):
        from contextlib import redirect_stdout
        from io import StringIO
        from datetime import datetime, timezone
        from saspay_verify_pilot import audit_merchant_scope
        calls = []
        def read(key, path):
            calls.append(path)
            if path.startswith("/merchant-balances/"):
                return {"results": [
                    {"merchant": self.merchant, "available_amount": "9999"}],
                    "next": None}
            return {"results": [
                {"merchant": self.merchant, "status": "SUCCESS",
                 "customer": "private-customer", "requested_amount": "1000"},
                {"merchant": "00000000-0000-0000-0000-000000000099",
                 "customer": "other-private"}], "next": None}
        out = StringIO()
        with (
            patch("saspay_verify_pilot._get_safe", side_effect=read),
            redirect_stdout(out),
        ):
            audit_merchant_scope(
                "sk_test_synthetic", self.merchant,
                datetime(2026, 11, 10, 5, 27, tzinfo=timezone.utc))
        output = out.getvalue()
        self.assertEqual(len(calls), 2)
        self.assertTrue(calls[0].startswith("/merchant-balances/?"))
        self.assertIn("created_at__gte=2026-11-10T05%3A17%3A00Z", calls[1])
        self.assertIn("created_at__lte=2026-11-10T05%3A37%3A00Z", calls[1])
        self.assertIn("Transactions du marchand Groupia configuré : 1", output)
        self.assertNotIn("private-customer", output)
        self.assertNotIn("other-private", output)
        self.assertNotIn("9999", output)
        self.assertNotIn("sk_test_synthetic", output)

    def test_scope_audit_rejects_unapproved_paths(self):
        from saspay_verify_pilot import _get_safe
        with patch("saspay_verify_pilot.urlopen") as request:
            with self.assertRaises(ValueError):
                _get_safe("sk_test_synthetic", "/payments/softpay/")
            request.assert_not_called()

    def test_verify_404_with_scope_audit_still_never_confirms(self):
        from contextlib import redirect_stdout
        from io import StringIO
        from datetime import datetime, timezone
        from saspay_verify_pilot import diagnose_verify_404
        uid = "253c31ba-9b54-44f8-958e-a6d78d1bbe87"
        out = StringIO()
        with (
            patch("saspay_verify_pilot.fetch_transaction_detail",
                  return_value={"id": uid, "merchant": self.merchant,
                                "status": "SUCCESS"}),
            patch("saspay_verify_pilot.fetch_transaction_page",
                  return_value={"results": [], "next": None}),
            patch("saspay_verify_pilot.audit_merchant_scope") as scope,
            redirect_stdout(out),
        ):
            code = diagnose_verify_404(
                "sk_test_synthetic", uid, self.merchant, True,
                datetime(2026, 11, 10, 5, 27, tzinfo=timezone.utc))
        self.assertEqual(code, 1)
        scope.assert_called_once()
        self.assertIn("aucune confirmation", out.getvalue())

    def test_unwrap_pagination_accepts_documented_bare_and_enveloped_results(self):
        from saspay_verify_pilot import unwrap_pagination
        data = {"results": [{"id": "safe-fixture"}], "next": "cursor"}
        self.assertEqual(unwrap_pagination(data), (data["results"], True))
        self.assertEqual(unwrap_pagination({"success": True, "data": data}),
                         (data["results"], True))
        self.assertEqual(unwrap_pagination({"success": True, "data": []}), ([], False))
        self.assertEqual(unwrap_pagination({"results": [], "next": None}), ([], False))

    def test_unwrap_pagination_rejects_undocumented_and_failed_envelopes(self):
        from saspay_verify_pilot import unwrap_pagination
        for payload in (
            {"success": False, "data": {"results": []}},
            {"success": True},
            {"success": True, "data": {"other": []}},
            {"detail": "no access"},
            {"data": {"results": []}},
            None,
        ):
            with self.subTest(payload=str(payload)):
                with self.assertRaises(ValueError):
                    unwrap_pagination(payload)

    def test_id_required_before_any_network_attempt(self):
        with patch("saspay_verify_pilot.urlopen") as request:
            with self.assertRaises(ValueError):
                fetch_payment("sk_test_fake", "not-a-payment-id")
            request.assert_not_called()


if __name__ == "__main__":
    unittest.main()
