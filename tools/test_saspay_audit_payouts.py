import unittest
from unittest.mock import Mock
from saspay_audit_payouts import audit, ROOT, validate_url, NoRedirect


class PayoutAuditTests(unittest.TestCase):
    merchant = "11111111-1111-4111-8111-111111111111"

    def run_audit(self, get):
        return audit("synthetic", self.merchant, "2026-11-14T00:00:00Z", "2026-11-15T12:00:00Z", get)

    def test_empty_is_never_definitive_proof(self):
        result = self.run_audit(lambda *_: {"results": [], "next": None})
        self.assertFalse(result["definitive_non_execution_proven"])

    def test_pagination_and_sensitive_fields_are_not_returned(self):
        get = Mock(side_effect=[{"results": [{"id": self.merchant, "status": "SUCCESS", "phone": "secret"}], "next": ROOT + "?cursor=2"}, {"success": True, "data": {"results": [], "next": None}}])
        result = self.run_audit(get)
        self.assertEqual(2, result["pages"])
        self.assertNotIn("secret", str(result))
        self.assertFalse(result["definitive_non_execution_proven"])

    def test_hostile_pagination_is_rejected_before_next_request(self):
        get = Mock(return_value={"results": [], "next": "https://attacker.test/"})
        with self.assertRaises(ValueError):
            self.run_audit(get)
        self.assertEqual(1, get.call_count)
        for url in ("http://api.saspay.me/api/v1/transactions/", ROOT + "evil/", "https://user@api.saspay.me/api/v1/transactions/"):
            with self.assertRaises(ValueError): validate_url(url)
        self.assertIsNone(NoRedirect().redirect_request(None, None, 302, None, None, ROOT))

    def test_error_or_missing_pagination_never_becomes_empty(self):
        for body in ({"success": False}, {}, [], {"results": "invalid"}):
            with self.assertRaises(ValueError):
                self.run_audit(lambda *_, body=body: body)
