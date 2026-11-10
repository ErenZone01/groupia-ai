"""Read-only SasPay pay-in verification for a previously initiated transaction.

This CLI never creates, retries, cancels, refunds or pays out anything.
It prints only the payment status and financial comparisons; no customer PII.
"""
import argparse
from decimal import Decimal, InvalidOperation
import json
import os
import re
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import UUID

from saspay_diagnostics import load_env_file

ROOT = "https://api.saspay.me/api/v1"


def money(obj, name):
    value = obj.get(name)
    if isinstance(value, bool) or not isinstance(value, (str, int, Decimal, float)):
        raise ValueError("Champ monétaire manquant : " + name)
    literal = str(value)
    if not re.fullmatch(r"[0-9]{1,14}(?:\.[0-9]{1,6})?", literal):
        raise ValueError("Champ monétaire invalide : " + name)
    try:
        result = Decimal(literal)
        if result < 0 or result >= Decimal("100000000000000"):
            raise ValueError("Champ monétaire hors limites : " + name)
        return result
    except InvalidOperation as exc:
        raise ValueError("Champ monétaire invalide : " + name) from exc


def safe_summary(payload, expected_merchant, expected_amount, cap):
    if not isinstance(payload, dict):
        raise ValueError("Réponse SasPay sans objet financier")
    data = payload.get("data", payload)
    if not isinstance(data, dict):
        raise ValueError("Réponse SasPay malformée")
    status = data.get("status")
    if status not in ("PENDING", "SUCCESS", "FAILED"):
        raise ValueError("Statut SasPay non reconnu")
    requested = money(data, "requested_amount")
    fee = money(data, "client_fee")
    charged = money(data, "debited_amount")
    net = money(data, "net_amount")
    mode = data.get("fee_charge_mode")
    merchant = data.get("merchant")
    checks = {
        "marchand": merchant == expected_merchant,
        "devise": data.get("currency") == "XOF",
        "sens/type": data.get("transaction_type") == "PAIEMENT"
        and data.get("flow_direction") == "INBOUND",
        "montant": requested == expected_amount,
        "frais ADD_ON": mode == "ADD_ON",
        "net marchand": net == requested,
        "débit total": charged == requested + fee,
        "plafond pilote": charged <= cap,
    }
    return {
        "status": status,
        "requested_amount": requested,
        "client_fee": fee,
        "debited_amount": charged,
        "net_amount": net,
        "checks": checks,
        "may_confirm_order": status == "SUCCESS" and all(checks.values()),
    }


def fetch_payment(key, payment_id):
    # Only this fixed read-only GET endpoint can be reached.
    payment_id = str(UUID(payment_id))
    req = Request(ROOT + "/payments/" + payment_id + "/verify/",
                  headers={"Authorization": "Bearer " + key, "Accept": "application/json"},
                  method="GET")
    with urlopen(req, timeout=12) as res:
        raw = res.read(262145)
        if len(raw) > 262144:
            raise ValueError("Réponse du fournisseur trop volumineuse")
        return json.loads(raw.decode("utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", default="Backend/.env")
    parser.add_argument("--payment-id", required=True, help="UUID de transaction SasPay existante")
    parser.add_argument("--expected-amount-xof", type=int, required=True)
    args = parser.parse_args()
    if args.expected_amount_xof < 1:
        parser.error("Montant attendu invalide")
    try:
        UUID(args.payment_id)
    except ValueError:
        parser.error("UUID de transaction invalide")
    env = dict(load_env_file(args.env_file))
    env.update(os.environ)
    key = env.get("SASPAY_API_KEY", "")
    merchant = env.get("SASPAY_MERCHANT_ID", "")
    if not key.startswith(("sk_live_", "sk_test_")):
        parser.error("Clé SasPay absente (valeur non affichée)")
    try:
        merchant = str(UUID(merchant))
        cap = Decimal(env.get("SASPAY_LIVE_PILOT_MAX_CHARGED_XOF", "2000"))
        if cap <= 0 or cap > Decimal("5000"):
            raise ValueError("Plafond pilote non autorisé")
        data = fetch_payment(key, args.payment_id)
        result = safe_summary(data, merchant, Decimal(args.expected_amount_xof), cap)
    except HTTPError as exc:
        print("Vérification refusée par SasPay (HTTP " + str(exc.code) + ").")
        return 1
    except (URLError, OSError):
        print("API SasPay injoignable")
        return 1
    except (ValueError, InvalidOperation, json.JSONDecodeError) as exc:
        print("Vérification impossible : " + str(exc))
        return 1
    print("Vérification SasPay LIVE en lecture seule : AUCUN DÉBIT")
    print("Statut fournisseur : " + result["status"])
    for name in ("requested_amount", "client_fee", "debited_amount", "net_amount"):
        print("  " + name + " : " + str(result[name]) + " XOF")
    for name, valid in result["checks"].items():
        print("  Vérification " + name + " : " + ("OK" if valid else "ÉCHEC"))
    print("Confirmation de commande possible : " +
          ("OUI (sous réserve des verrous métier Groupia)" if result["may_confirm_order"]
           else "NON"))
    print("Le résultat n'altère pas l'état Groupia : celui-ci doit être réconcilié séparément.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
