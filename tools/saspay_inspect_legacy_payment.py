"""GET-only legacy payment verification; no mutation or provider retry, no PII output."""
import argparse
from decimal import Decimal
import json
import os
from urllib.error import HTTPError
from urllib.request import Request, build_opener
from uuid import UUID

from saspay_diagnostics import load_env_file
from saspay_inspect_payout import NoRedirect, safe_money, same_uuid


def get(key, transaction):
    if key.startswith("sk_live_"):
        base = "https://api.saspay.me/api/v1"
    elif key.startswith("sk_test_"):
        base = "https://sandbox-api.saspay.me/api/v1"
    else:
        raise ValueError("INVALID_KEY")
    request = Request(base + "/payments/" + str(UUID(transaction)) + "/verify/",
                      method="GET", headers={"Authorization": "Bearer " + key,
                                             "Accept": "application/json"})
    with build_opener(NoRedirect()).open(request, timeout=15) as response:
        if response.status != 200:
            raise ValueError("HTTP_STATUS")
        body = response.read(262145)
        if len(body) > 262144:
            raise ValueError("RESPONSE_TOO_LARGE")
        data = json.loads(body, parse_float=Decimal)
        if not isinstance(data, dict):
            raise ValueError("INVALID_OBJECT")
        if "success" in data:
            if data["success"] is not True or not isinstance(data.get("data"), dict):
                raise ValueError("INVALID_ENVELOPE")
            data = data["data"]
        return data


def report(data, transaction, merchant, amount, fee):
    checks = {
        "transaction": same_uuid(data.get("id"), transaction),
        "merchant": same_uuid(data.get("merchant"), merchant),
        "type": data.get("transaction_type") == "PAIEMENT",
        "direction": data.get("flow_direction") == "INBOUND",
        "currency": data.get("currency") == "XOF",
        "fee_mode": data.get("fee_charge_mode") == "ADD_ON",
        "principal": safe_money(data, "requested_amount") == amount,
        "net": safe_money(data, "net_amount") == amount,
        "fee": safe_money(data, "client_fee") == fee,
        "charged": safe_money(data, "debited_amount") == amount + fee,
    }
    state = data.get("status")
    if state not in ("PENDING", "SUCCESS", "FAILED", "CANCELLED"):
        state = "UNKNOWN"
    return {"status": state, "checks": checks, "consistent": all(checks.values()) and state != "UNKNOWN"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", default="Backend/.env")
    parser.add_argument("--transaction-id", required=True)
    parser.add_argument("--expected-amount-xof", required=True, type=Decimal)
    parser.add_argument("--expected-fee-xof", required=True, type=Decimal)
    args = parser.parse_args()
    try:
        transaction = str(UUID(args.transaction_id))
        if (not args.expected_amount_xof.is_finite() or args.expected_amount_xof <= 0
                or not args.expected_fee_xof.is_finite() or args.expected_fee_xof < 0):
            raise ValueError("INVALID_EXPECTATION")
        settings = dict(load_env_file(args.env_file))
        settings.update(os.environ)
        merchant = str(UUID(settings.get("SASPAY_MERCHANT_ID", "")))
        data = get(settings.get("SASPAY_API_KEY", ""), transaction)
        result = report(data, transaction, merchant, args.expected_amount_xof, args.expected_fee_xof)
        print(json.dumps(result, sort_keys=True))
        return 0 if result["consistent"] else 1
    except HTTPError as ex:
        print(json.dumps({"error": "PROVIDER_HTTP", "status": ex.code}))
    except Exception:
        # Never print arbitrary exception messages (URL, payload or credentials).
        print(json.dumps({"error": "VERIFICATION_UNAVAILABLE"}))
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
