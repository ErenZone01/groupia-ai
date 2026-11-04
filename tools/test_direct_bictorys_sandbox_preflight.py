"""Offline contract tests; CI must never reach Bictorys or create a charge."""

import contextlib
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import direct_bictorys_sandbox_preflight as pilot


ROOT = Path(__file__).resolve().parents[1]


def example_pair(financial="PAYMENT_IN_PROGRESS", available=False, order="PENDING_PAYMENT_INTEGRATION"):
    tracking = {
        "orderNumber": "DIR-000000000001",
        "currency": "XOF",
        "acceptedTotalXof": 10300,
        "status": order,
    }
    payment = {
        "orderNumber": "DIR-000000000001",
        "currency": "XOF",
        "acceptedTotalXof": 10300,
        "financialStatus": financial,
        "paymentAvailable": available,
    }
    return tracking, payment


class FakeResponse(io.BytesIO):
    status = 200


class FakeOpener:
    def __init__(self, response):
        self.response = response
        self.requests = []

    def open(self, request, timeout):
        self.requests.append(request)
        return FakeResponse(json.dumps(self.response).encode("utf-8"))


class DirectBictorysPilotTests(unittest.TestCase):
    def test_gates_are_explicitly_wired_and_default_disabled(self):
        app = (ROOT / "Backend/src/main/resources/application.properties").read_text()
        env = (ROOT / "Backend/.env.example").read_text()
        config = {
            "groupia.direct-sales.checkout.enabled": "GROUPIA_DIRECT_CHECKOUT_ENABLED",
            "groupia.direct-sales.payment-obligation.auto-prepare.enabled":
                "GROUPIA_DIRECT_PAYMENT_AUTO_PREPARE_ENABLED",
            "groupia.direct-sales.payment-initiation.enabled":
                "GROUPIA_DIRECT_PAYMENT_INITIATION_ENABLED",
        }
        for key, variable in config.items():
            with self.subTest(key=key):
                self.assertIn(key + "=$" + "{" + variable + ":false}", app)
                self.assertIn(f"{variable}=false", env)
        self.assertIn("BICTORYS_ENABLED=false", env)
        self.assertIn("GROUPIA_BICTORYS_PAYOUTS_ENABLED=false", env)
        self.assertIn("GROUPIA_MERCHANT_PAYOUTS_ENABLED=false", env)

    def test_http_loopback_only_and_no_redirect_following(self):
        self.assertEqual(
            pilot.local_base_url("http://127.0.0.1:8080/"), "http://127.0.0.1:8080"
        )
        for bad in [
            "https://example.com", "http://example.com:8080",
            "http://127.0.0.1", "http://user@127.0.0.1:8080",
            "http://127.0.0.1:8080/private", "http://127.0.0.1:8080/?token=secret",
            "http://127.0.0.1:8080/#access=secret",
        ]:
            with self.subTest(base=bad):
                with self.assertRaises(pilot.UnsafePreflight):
                    pilot.local_base_url(bad)
        self.assertIsNone(pilot.NoRedirects().redirect_request(None, None, 302, "", {}, "https://elsewhere.test"))

    def test_token_only_in_post_body_on_two_read_only_routes(self):
        token = "A" * 43
        opener = FakeOpener(example_pair()[1])
        data = pilot.request_private_status(
            "http://localhost:8080", token, "/api/public/direct-orders/payment-status", opener
        )
        self.assertEqual(data["financialStatus"], "PAYMENT_IN_PROGRESS")
        req = opener.requests[0]
        self.assertEqual(req.get_method(), "POST")
        self.assertNotIn(token, req.full_url)
        self.assertEqual(json.loads(req.data)["token"], token)
        self.assertNotIn("payments", req.full_url.rsplit("/", 1)[-1])
        with self.assertRaises(pilot.UnsafePreflight):
            pilot.request_private_status(
                "http://localhost:8080", token, "/api/public/direct-orders/payments", opener
            )

    def test_payment_pending_is_never_reported_as_settled(self):
        tracking, payment = example_pair()
        line = pilot.verify_pair(tracking, payment)
        self.assertIn("PAYMENT_IN_PROGRESS", line)
        self.assertIn("Paiement proposé : non", line)

    def test_verified_only_when_both_server_views_agree(self):
        tracking, payment = example_pair("SETTLEMENT_VERIFIED", False, "PAYMENT_CONFIRMED")
        self.assertIn("SETTLEMENT_VERIFIED", pilot.verify_pair(tracking, payment))
        tracking["status"] = "PENDING_PAYMENT_INTEGRATION"
        with self.assertRaises(pilot.UnsafePreflight):
            pilot.verify_pair(tracking, payment)

    def test_incoherent_amount_reference_or_payment_permission_fails_closed(self):
        for mutation in ["amount", "reference", "amount_float", "bad_availability"]:
            with self.subTest(mutation=mutation):
                tracking, payment = example_pair()
                if mutation == "amount":
                    payment["acceptedTotalXof"] = 20000
                elif mutation == "reference":
                    payment["orderNumber"] = "DIR-000000000002"
                elif mutation == "amount_float":
                    payment["acceptedTotalXof"] = 10300.0
                else:
                    payment["paymentAvailable"] = True
                with self.assertRaises(pilot.UnsafePreflight):
                    pilot.verify_pair(tracking, payment)

    def test_cli_never_prints_secret_or_starts_provider_charge(self):
        token = "A" * 43
        tracking, payment = example_pair()
        invoked = []

        def fake_read(base, secret, path):
            self.assertEqual(secret, token)
            invoked.append(path)
            return tracking if path.endswith("/lookup") else payment

        output = io.StringIO()
        with patch.object(pilot.getpass, "getpass", return_value=token), patch.object(
            pilot, "request_private_status", side_effect=fake_read
        ), contextlib.redirect_stdout(output):
            self.assertEqual(pilot.main(["--base-url", "http://localhost:8080"]), 0)
        self.assertNotIn(token, output.getvalue())
        self.assertEqual(
            invoked,
            ["/api/public/direct-orders/lookup", "/api/public/direct-orders/payment-status"],
        )


if __name__ == "__main__":
    unittest.main()
