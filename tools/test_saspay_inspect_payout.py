"""No-network tests for the read-only SasPay payout evidence diagnostic."""
import io
import json
import unittest
from contextlib import redirect_stdout
from decimal import Decimal
from unittest.mock import patch

from saspay_inspect_payout import get, report, safe_enum

PAYOUT = "489b02d1-fe47-4e8a-a65c-f62e57d15fa0"
MERCHANT = "131c3f1e-1b3c-4970-89e2-b0b79d1ee8be"


def fixture():
    return {
        "id": PAYOUT,
        "merchant": MERCHANT,
        "transaction_type": "RETRAIT",
        "flow_direction": "OUTBOUND",
        "currency": "XOF",
        "fee_charge_mode": "DEDUCTED",
        "requested_amount": "1000.00",
        "client_fee": "37.00",
        "debited_amount": "1000.00",
        "net_amount": "963.00",
        "status": "SUCCESS",
        "operation_msisdn": "+221779998577",
        "network_code": "wave_sn",
        "recipient": {"name": "PRIVATE-OWNER", "msisdn": "PRIVATE"},
        "reference": "PRIVATE-PAYOUT-KEY",
        "metadata": {"secret": "PRIVATE-METADATA"},
    }


class SasPayPayoutEvidenceTests(unittest.TestCase):
    def test_valid_financials_but_never_reports_sensitive_fields(self):
        result = report(fixture(), PAYOUT, MERCHANT, Decimal("1000"), Decimal("37"))
        self.assertTrue(all(result["checks"].values()))
        self.assertEqual(result["status"], "SUCCESS")
        self.assertEqual(result["type"], "RETRAIT")
        live = fixture()
        live["transaction_type"] = "TRANSFERT"
        self.assertTrue(all(report(live, PAYOUT, MERCHANT, Decimal("1000"), Decimal("37"))["checks"].values()))
        for secret in ("PRIVATE", "8577", MERCHANT, PAYOUT, "wave_sn"):
            self.assertNotIn(secret, str(result))

    def test_mismatch_isolated_without_relaxing_payout_contract(self):
        fields = {
            "merchant": "00000000-0000-0000-0000-000000000001",
            "transaction_type": "PAIEMENT",
            "flow_direction": "INBOUND",
            "currency": "USD",
            "fee_charge_mode": "ADD_ON",
            "id": "00000000-0000-0000-0000-000000000001",
        }
        for field, value in fields.items():
            with self.subTest(field=field):
                data = fixture()
                data[field] = value
                result = report(data, PAYOUT, MERCHANT, Decimal("1000"), Decimal("37"))
                self.assertFalse(all(result["checks"].values()))

    def test_bad_financial_values_fail_closed(self):
        for field in ("requested_amount", "client_fee", "debited_amount", "net_amount"):
            for wrong in (None, True, "-1", "9999", {}, "0.0000001"):
                with self.subTest(field=field, wrong=wrong):
                    data = fixture()
                    data[field] = wrong
                    result = report(data, PAYOUT, MERCHANT, Decimal("1000"), Decimal("37"))
                    self.assertFalse(all(result["checks"].values()))

    def test_untrusted_enum_string_never_leaks(self):
        self.assertEqual(safe_enum({"status": "secret@email.test"}, "status"), "VALEUR_NON_CONFORME")
        self.assertEqual(safe_enum({"status": "PENDING"}, "status"), "PENDING")

    def test_only_two_bounded_get_paths_and_no_redirect(self):
        class Response:
            status = 200
            def __enter__(self):
                return self
            def __exit__(self, *_):
                pass
            def read(self, count):
                self.requested_limit = count
                return json.dumps({"success": True, "data": fixture()}).encode()
        calls = []
        def opener(*handlers):
            class Transport:
                def open(self, req, timeout):
                    calls.append((req.get_method(), req.full_url, timeout, handlers))
                    return Response()
            return Transport()
        with patch("saspay_inspect_payout.build_opener", side_effect=opener):
            self.assertEqual(get("sk_test_private", PAYOUT, False)["status"], "SUCCESS")
            self.assertEqual(get("sk_test_private", PAYOUT, True)["status"], "SUCCESS")
        self.assertEqual([x[0] for x in calls], ["GET", "GET"])
        self.assertTrue(calls[0][1].endswith("/payouts/" + PAYOUT + "/verify/"))
        self.assertTrue(calls[1][1].endswith("/transactions/" + PAYOUT + "/"))
        self.assertTrue(all(x[2] == 15 for x in calls))

    def test_bad_id_never_reaches_network(self):
        with patch("saspay_inspect_payout.build_opener") as open_transport:
            with self.assertRaises(ValueError):
                get("sk_test_private", "https://evil.invalid/pay", True)
            open_transport.assert_not_called()

    def test_unrecognized_envelope_rejected(self):
        class Response:
            status = 200
            def __enter__(self):
                return self
            def __exit__(self, *_):
                pass
            def read(self, count):
                return b'{"success":false,"data":{"status":"SUCCESS"}}'
        class Transport:
            def open(self, req, timeout):
                return Response()
        with patch("saspay_inspect_payout.build_opener", return_value=Transport()):
            with self.assertRaises(ValueError):
                get("sk_test_private", PAYOUT, True)


if __name__ == "__main__":
    unittest.main()
