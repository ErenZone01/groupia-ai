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

    def test_id_required_before_any_network_attempt(self):
        with patch("saspay_verify_pilot.urlopen") as request:
            with self.assertRaises(ValueError):
                fetch_payment("sk_test_fake", "not-a-payment-id")
            request.assert_not_called()


if __name__ == "__main__":
    unittest.main()
