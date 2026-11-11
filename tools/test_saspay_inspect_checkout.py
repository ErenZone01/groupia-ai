"""Network-free contract tests for hosted checkout diagnostic. No financial side effects."""
import io
import json
import unittest
from contextlib import redirect_stdout
from decimal import Decimal
from unittest.mock import patch
from uuid import UUID

from saspay_inspect_checkout import get_session, hosted_url_host, summary, unwrap

SESSION = "a9b67211-150f-4c71-885c-123450000001"
ATTEMPT = "2176adb7-9d7b-4db7-9b5c-c416336916c9"
MERCHANT = "131c3f1e-1b3c-4970-89e2-b0b79d1ee8be"


class HostedCheckoutDiagnosticTests(unittest.TestCase):
    def detail(self, url="https://pay.saspay.me/checkout/private-token"):
        return {
            "id": SESSION,
            "merchant": MERCHANT,
            "metadata": {"groupia_payment_attempt_id": ATTEMPT},
            "amount": "1000.00",
            "currency": "XOF",
            "customer_email": "private@example.test",
            "checkout_url": url,
        }

    def test_valid_session_reports_only_safe_hostname(self):
        data = summary(
            self.detail(), {"id": SESSION, "status": "PENDING"},
            SESSION, ATTEMPT, MERCHANT, Decimal("1000")
        )
        self.assertTrue(all(data["checks"].values()))
        self.assertEqual(data["host"], "pay.saspay.me")
        self.assertTrue(data["usable"])
        self.assertNotIn("private", str(data))

    def test_pending_session_with_missing_url_is_not_usable(self):
        data = summary(self.detail(None), {"id": SESSION, "status": "PENDING"},
                       SESSION, ATTEMPT, MERCHANT, Decimal("1000"))
        self.assertFalse(data["url_present"])
        self.assertFalse(data["usable"])

    def test_invalid_metadata_or_amount_never_recovers(self):
        detail = self.detail()
        detail["metadata"]["groupia_payment_attempt_id"] = str(UUID(MERCHANT))
        self.assertFalse(summary(detail, {"id": SESSION, "status": "PENDING"},
                                 SESSION, ATTEMPT, MERCHANT, Decimal("1000"))["usable"])
        detail["metadata"]["groupia_payment_attempt_id"] = ATTEMPT
        detail["amount"] = "999.99"
        self.assertFalse(summary(detail, {"id": SESSION, "status": "PENDING"},
                                 SESSION, ATTEMPT, MERCHANT, Decimal("1000"))["usable"])

    def test_paid_session_is_not_checkout_usable_or_proof_of_payment(self):
        data = summary(self.detail(), {"id": SESSION, "status": "PAID"},
                       SESSION, ATTEMPT, MERCHANT, Decimal("1000"))
        self.assertFalse(data["usable"])
        self.assertEqual(data["status"], "PAID")

    def test_unsafe_urls_never_expose_userinfo_or_host(self):
        for url in [
            "http://pay.saspay.me/checkout/test",
            "https://evil@example.org/checkout/x",
            "https://pay.saspay.me:8443/checkout/test",
            "https://pay.saspay.me/checkout/test#private",
            "javascript:alert(1)",
        ]:
            with self.subTest(url=url):
                host, safe = hosted_url_host(url)
                self.assertIsNone(host)
                self.assertFalse(safe)

    def test_get_session_is_get_only_and_normalizes_uuid(self):
        class Response:
            def __enter__(self): return self
            def __exit__(self, *_): return None
            def read(self, n):
                return json.dumps({"success": True, "data": {"id": SESSION}}).encode()
        calls = []
        def response(req, timeout):
            calls.append((req.get_method(), req.full_url))
            return Response()
        with patch("saspay_inspect_checkout.urlopen", side_effect=response):
            self.assertEqual(get_session("sk_test_fake", SESSION)["id"], SESSION)
            self.assertEqual(get_session("sk_test_fake", SESSION, status=True)["id"], SESSION)
        self.assertEqual([method for method, _ in calls], ["GET", "GET"])
        self.assertEqual(calls[0][1],
                         "https://api.saspay.me/api/v1/checkout-sessions/" + SESSION + "/")
        self.assertTrue(calls[1][1].endswith("/" + SESSION + "/status/"))

    def test_bad_session_id_prevents_any_network_call(self):
        with patch("saspay_inspect_checkout.urlopen") as call:
            with self.assertRaises(ValueError):
                get_session("sk_test_fake", "not-an-uuid")
            call.assert_not_called()

    def test_negative_envelope_is_rejected(self):
        with self.assertRaises(ValueError):
            unwrap({"success": False, "data": self.detail()})


if __name__ == "__main__":
    unittest.main()
