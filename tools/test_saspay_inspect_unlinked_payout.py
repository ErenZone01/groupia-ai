"""Contract tests for safe, GET-only historical SasPay reconciliation."""
import importlib.util
import unittest
from decimal import Decimal
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "saspay_inspect_unlinked_payout",
    Path(__file__).with_name("saspay_inspect_unlinked_payout.py"),
)
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)

MERCHANT = "8a9b0c1d-2e3f-4a5b-8c6d-7e8f9a0b1c2d"
ID = "a4b5c6d7-e8f9-4a0b-8c1d-2e3f4a5b6c7d"
DATE = "2026-11-14T06:23:00Z"


def row(**values):
    result = dict(id=ID, merchant=MERCHANT, currency="XOF", flow_direction="OUTBOUND",
                  transaction_type="TRANSFERT", requested_amount="1000.00",
                  created_at=DATE, status="SUCCESS")
    result.update(values)
    return result


class UnlinkedPayoutReadOnlyTests(unittest.TestCase):
    def test_rejects_cross_origin_and_non_transactions_path(self):
        for target in ["https://evil.invalid/api/v1/transactions/?cursor=x",
                       "http://api.saspay.me/api/v1/transactions/?cursor=x",
                       "https://api.saspay.me/api/v1/payouts/initialize/?cursor=x",
                       "https://api.saspay.me/api/v1/transactions/?cursor=x&redirect=evil",
                       "https://api.saspay.me/api/v1/transactions/?cursor=x#fragment"]:
            with self.subTest(target=target), self.assertRaises(module.ReadOnlyDiagnosticError):
                module.next_cursor(target)

    def test_cursor_only_reconstructed_as_internal_url(self):
        self.assertEqual("hello", module.next_cursor(
            "https://api.saspay.me/api/v1/transactions/?cursor=hello"))
        url = module.request_url({"merchant": MERCHANT}, "hello")
        self.assertTrue(url.startswith("https://api.saspay.me/api/v1/transactions/?"))
        self.assertIn("merchant=", url)
        self.assertIn("cursor=hello", url)

    def test_two_pages_only_candidate_identifiers_printable(self):
        calls = []

        def get(url, key):
            calls.append((url, key))
            return ({"results": [row()], "next":
                     "https://api.saspay.me/api/v1/transactions/?cursor=second"}
                    if len(calls) == 1 else {"results": [row(id="ba7119fe-d78d-4404-a5ac-b4138f5960e8",
                                                              transaction_type="PAIEMENT")], "next": None})

        p, n, found = module.scan_pages(
            "sk_live_test", MERCHANT, module.as_utc("2026-11-14T00:00:00Z"),
            module.as_utc("2026-11-16T00:00:00Z"), Decimal("1000"), 3, get)
        self.assertEqual((2, 2), (p, n))
        self.assertEqual([ID], [x["provider_id"] for x in found])
        self.assertEqual(2, len(calls))
        self.assertEqual("sk_live_test", calls[0][1])

    def test_incomplete_scan_never_claims_absence(self):
        get = lambda url, key: {"results": [], "next":
                                "https://api.saspay.me/api/v1/transactions/?cursor=another"}
        with self.assertRaisesRegex(module.ReadOnlyDiagnosticError, "PAGINATION_INCOMPLETE"):
            module.scan_pages("key", MERCHANT, module.as_utc("2026-11-14T00:00:00Z"),
                              module.as_utc("2026-11-16T00:00:00Z"), Decimal(1000), 1, get)

    def test_duplicate_provider_id_fails_closed(self):
        count = [0]
        def get(url, key):
            count[0] += 1
            return {"results": [row()], "next":
                    "https://api.saspay.me/api/v1/transactions/?cursor=second" if count[0] == 1 else None}
        with self.assertRaisesRegex(module.ReadOnlyDiagnosticError, "DUPLICATE_TRANSACTION"):
            module.scan_pages("key", MERCHANT, module.as_utc("2026-11-14T00:00:00Z"),
                              module.as_utc("2026-11-16T00:00:00Z"), Decimal(1000), 2, get)

    def test_mismatched_identity_never_yields_candidate(self):
        get = lambda url, key: {"results": [row(merchant="5b6c7d8e-9f01-4a2b-8c3d-4e5f6a7b8c9d")], "next": None}
        _, _, found = module.scan_pages("key", MERCHANT, module.as_utc("2026-11-14T00:00:00Z"),
                                       module.as_utc("2026-11-16T00:00:00Z"), Decimal(1000), 2, get)
        self.assertEqual([], found)


if __name__ == "__main__":
    unittest.main()
