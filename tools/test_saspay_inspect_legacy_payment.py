import json
import unittest
from decimal import Decimal
from unittest.mock import MagicMock, patch
from saspay_inspect_legacy_payment import get, report

TX = "11111111-1111-4111-8111-111111111111"
ACCOUNT = "22222222-2222-4222-8222-222222222222"


class LegacyDiagnosticTests(unittest.TestCase):
    def evidence(self):
        return dict(id=TX, merchant=ACCOUNT, transaction_type="PAIEMENT", flow_direction="INBOUND",
                    currency="XOF", fee_charge_mode="ADD_ON", requested_amount="1000.00",
                    client_fee="37.50", net_amount="1000", debited_amount="1037.50", status="FAILED")

    def test_checks_every_financial_field_and_never_leaks_external_values(self):
        for field in self.evidence():
            data = self.evidence()
            data[field] = "sensitive-value"
            result = report(data, TX, ACCOUNT, Decimal(1000), Decimal("37.50"))
            self.assertFalse(result["consistent"], field)
            self.assertNotIn("sensitive-value", json.dumps(result))

    def test_all_documented_states_require_full_proof(self):
        for status in ("PENDING", "SUCCESS", "FAILED", "CANCELLED"):
            data = self.evidence()
            data["status"] = status
            self.assertTrue(report(data, TX, ACCOUNT, Decimal(1000), Decimal("37.50"))["consistent"])

    @patch("saspay_inspect_legacy_payment.build_opener")
    def test_test_key_uses_sandbox_and_only_one_get(self, opener):
        response = MagicMock()
        response.status = 200
        response.read.return_value = json.dumps(self.evidence()).encode()
        opener.return_value.open.return_value.__enter__.return_value = response
        get("sk_test_synthetic", TX)
        request = opener.return_value.open.call_args.args[0]
        self.assertEqual(request.get_method(), "GET")
        self.assertEqual(request.full_url, "https://sandbox-api.saspay.me/api/v1/payments/"+TX+"/verify/")
        opener.return_value.open.assert_called_once()
