"""Offline checks of the vendor-published SasPay OpenAPI snapshot.

No network, no API credentials, no financial action. A snapshot change must be
reviewed with the upstream spec before production code is changed.
"""

import json
import pathlib
import unittest

SPEC = pathlib.Path(__file__).resolve().parents[1] / "Backend/docs/saspay/openapi.json"

CRITICAL_OPERATIONS = {
    ("post", "/checkout-sessions/"): "201",
    ("get", "/checkout-sessions/"): "200",
    ("get", "/checkout-sessions/{id}/"): "200",
    ("get", "/checkout-sessions/{id}/status/"): "200",
    ("get", "/payments/{payment_id}/verify/"): "200",
    ("post", "/payments/softpay/"): "201",
    ("post", "/payments/{payment_id}/retry/"): "200",
    ("post", "/payouts/initialize/"): "201",
    ("get", "/payouts/{payout_id}/verify/"): "200",
    ("get", "/transactions/"): "200",
    ("get", "/transactions/{id}/"): "200",
    ("get", "/transaction-attempts/"): "200",
    ("get", "/transaction-status-logs/"): "200",
    ("get", "/webhook-logs/"): "200",
    ("get", "/pricing/my-rates/"): "200",
    ("get", "/merchant-balances/"): "200",
}

def operation(spec, method, path):
    return spec["paths"][path][method]

def idempotency_advertised(spec, method, path):
    params = operation(spec, method, path).get("parameters", [])
    return any(
        item.get("$ref", "").endswith("/IdempotencyKey")
        or (
            item.get("name", "").lower() == "idempotency-key"
            and item.get("in") == "header"
        )
        for item in params
    )

class SaspayOpenApiSnapshotTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.spec = json.loads(SPEC.read_text(encoding="utf-8"))

    def test_money_paths_and_status_codes_are_pinned(self):
        for (method, path), status in CRITICAL_OPERATIONS.items():
            with self.subTest(method=method, path=path):
                self.assertIn(status, operation(self.spec, method, path)["responses"])

    def test_only_documented_money_post_idempotency_is_assumed(self):
        for path in ("/payments/softpay/", "/payouts/initialize/", "/payments/{payment_id}/retry/"):
            with self.subTest(path=path):
                self.assertTrue(idempotency_advertised(self.spec, "post", path))
        self.assertFalse(idempotency_advertised(self.spec, "post", "/checkout-sessions/"))
        # Checkout POST remains non-retryable on ambiguous outcomes until
        # the vendor explicitly documents and confirms Idempotency-Key support.

    def test_no_undocumented_refund_endpoint_or_transfer_is_assumed(self):
        paths = self.spec["paths"]
        self.assertFalse(any("refund" in path.lower() for path in paths))
        self.assertIn("post", paths["/wallet-transfers/"])
        # Wallet-transfer support in a vendor API is NOT an authorization
        # for a marketplace to operate pooled money transfers.

    def test_snapshotted_api_is_a_versioned_contract(self):
        self.assertTrue(self.spec.get("openapi", "").startswith("3."))
        self.assertEqual(self.spec.get("info", {}).get("version"), "1.0.0")

if __name__ == "__main__":
    unittest.main()
