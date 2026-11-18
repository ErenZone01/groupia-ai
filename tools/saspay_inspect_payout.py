"""Read-only SasPay merchant-payout verification diagnostic; never dispatches transfers.

Queries exactly two fixed GET resources for an existing transaction identifier.
Reports safe enum values, field presence and expected-value comparisons only.
No tokens, phone numbers, merchant identifiers, metadata or raw JSON are printed.
A SUCCESS in this diagnostic never updates Groupia or proves a payout on its own.
"""
import argparse
from decimal import Decimal, InvalidOperation
import json
import os
import re
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import UUID

from saspay_diagnostics import load_env_file

ROOT = "https://api.saspay.me/api/v1"
MAX_BYTES = 262144
FIELDS = (
    "id", "merchant", "transaction_type", "flow_direction", "currency",
    "fee_charge_mode", "requested_amount", "client_fee", "debited_amount",
    "net_amount", "status", "operation_msisdn", "recipient", "recipient_msisdn",
    "network_code", "updated_at",
)
ALIASES = ("type", "flow", "amount", "fee", "charged", "network", "msisdn", "reference")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def get(key, payout_id, detail):
    """GET-only, URL constructed from a UUID; redirects rejected, bodies bounded."""
    canonical = str(UUID(payout_id))
    path = ("/transactions/" if detail else "/payouts/") + canonical
    path += "/" if detail else "/verify/"
    req = Request(ROOT + path, method="GET", headers={
        "Authorization": "Bearer " + key, "Accept": "application/json"})
    with build_opener(NoRedirect()).open(req, timeout=15) as response:
        if response.status != 200:
            raise ValueError("Statut HTTP inattendu")
        raw = response.read(MAX_BYTES + 1)
        if len(raw) > MAX_BYTES:
            raise ValueError("Reponse SasPay trop volumineuse")
        payload = json.loads(raw.decode("utf-8"), parse_float=Decimal)
        if not isinstance(payload, dict):
            raise ValueError("Objet SasPay requis")
        if "success" in payload:
            if payload["success"] is not True or not isinstance(payload.get("data"), dict):
                raise ValueError("Enveloppe SasPay invalide")
            payload = payload["data"]
        return payload


def safe_enum(data, name):
    value = data.get(name)
    if not isinstance(value, str):
        return "ABSENT" if value is None else "TYPE_INVALIDE"
    return value if re.fullmatch(r"[a-zA-Z_][a-zA-Z0-9_.-]{0,39}", value) else "VALEUR_NON_CONFORME"


def safe_money(data, name):
    value = data.get(name)
    if isinstance(value, bool) or not isinstance(value, (str, int, Decimal)):
        return None
    literal = str(value)
    if not re.fullmatch(r"[0-9]{1,14}(?:\.[0-9]{1,6})?", literal):
        return None
    try:
        amount = Decimal(literal)
        return amount if amount.is_finite() else None
    except InvalidOperation:
        return None


def same_uuid(value, expected):
    try:
        return UUID(str(value)) == UUID(str(expected))
    except (ValueError, TypeError, AttributeError):
        return False


def report(data, payout_id, merchant_id, expected_amount, expected_fee):
    """Safe comparisons; never returns provider objects or personally identifying values."""
    if not isinstance(data, dict):
        raise ValueError("Objet financier attendu")
    status = safe_enum(data, "status")
    results = {
        "id_transaction": same_uuid(data.get("id"), payout_id),
        "compte_marchand": same_uuid(data.get("merchant"), merchant_id),
        "type_RETRAIT": data.get("transaction_type") == "RETRAIT",
        "sens_OUTBOUND": data.get("flow_direction") == "OUTBOUND",
        "devise_XOF": data.get("currency") == "XOF",
        "mode_DEDUCTED": data.get("fee_charge_mode") == "DEDUCTED",
    }
    requested = safe_money(data, "requested_amount")
    fee = safe_money(data, "client_fee")
    charged = safe_money(data, "debited_amount")
    net = safe_money(data, "net_amount")
    results.update({
        "montant_demande": requested == expected_amount,
        "frais_attendus": fee == expected_fee,
        "montant_debite": charged == expected_amount,
        "net_attendu": net == expected_amount - expected_fee,
        "identite_financiere": None not in (requested, fee, charged, net)
        and requested == charged == expected_amount and net == requested - fee,
    })
    fields = [x for x in FIELDS + ALIASES if x in data]
    # Field names are predefined; unknown names and actual values never leave this process.
    return {"status": status, "presence": fields,
            "type": safe_enum(data, "transaction_type"),
            "sens": safe_enum(data, "flow_direction"),
            "devise": safe_enum(data, "currency"),
            "mode": safe_enum(data, "fee_charge_mode"),
            "checks": results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", default="Backend/.env")
    parser.add_argument("--payout-id", required=True, help="UUID transaction SasPay existante")
    parser.add_argument("--expected-amount-xof", type=int, default=1000)
    parser.add_argument("--expected-fee-xof", type=int, default=37)
    args = parser.parse_args()
    try:
        payout_id = str(UUID(args.payout_id))
        if args.expected_amount_xof <= 0 or args.expected_fee_xof < 0 \
                or args.expected_fee_xof >= args.expected_amount_xof:
            raise ValueError()
    except ValueError:
        parser.error("Identifiant ou montants attendus invalides")
    settings = dict(load_env_file(args.env_file))
    settings.update(os.environ)
    key = settings.get("SASPAY_API_KEY", "")
    if not key.startswith(("sk_live_", "sk_test_")):
        parser.error("Cle SasPay absente ou invalide (valeur masquee)")
    try:
        merchant = str(UUID(settings.get("SASPAY_MERCHANT_ID", "")))
    except ValueError:
        parser.error("Compte marchand SasPay absent ou invalide (valeur masquee)")
    print("Diagnostic SasPay READ ONLY / aucune mutation / aucun POST")
    print("Environnement cle : " + ("LIVE" if key.startswith("sk_live_") else "TEST"))
    result_ok = True
    for detail, title in ((False, "VERIFY"), (True, "DETAILS")):
        print("\n" + title + " :")
        try:
            data = get(key, payout_id, detail=detail)
            result = report(
                data, payout_id, merchant, Decimal(args.expected_amount_xof),
                Decimal(args.expected_fee_xof))
            print("  statut : " + result["status"])
            for item in ("type", "sens", "devise", "mode"):
                print("  " + item + " : " + result[item])
            print("  champs presents (liste autorisee) : " + ", ".join(result["presence"]))
            for field, passed in result["checks"].items():
                print("  " + field + " : " + ("OK" if passed else "ECHEC"))
            if not all(result["checks"].values()):
                result_ok = False
        except HTTPError as exc:
            print("  GET refuse : HTTP " + str(exc.code))
            result_ok = False
        except (URLError, OSError, ValueError, json.JSONDecodeError):
            print("  GET impossible ou reponse invalide")
            result_ok = False
    print("\nAucun transfert, ecriture SQL ou changement de statut n'a ete effectue.")
    print("Ces verifications ne remplacent pas la reconciliation authentifiee Groupia.")
    return 0 if result_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())

"""Read-only SasPay merchant-payout verification diagnostic; never dispatches transfers.

Queries exactly two fixed GET resources for an existing transaction identifier.
Reports safe enum values, field presence and expected-value comparisons only.
No tokens, phone numbers, merchant identifiers, metadata or raw JSON are printed.
A SUCCESS in this diagnostic never updates Groupia or proves a payout on its own.
"""
import argparse
from decimal import Decimal, InvalidOperation
import json
import os
import re
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import UUID

from saspay_diagnostics import load_env_file

ROOT = "https://api.saspay.me/api/v1"
MAX_BYTES = 262144
FIELDS = (
    "id", "merchant", "transaction_type", "flow_direction", "currency",
    "fee_charge_mode", "requested_amount", "client_fee", "debited_amount",
    "net_amount", "status", "operation_msisdn", "recipient", "recipient_msisdn",
    "network_code", "updated_at",
)
ALIASES = ("type", "flow", "amount", "fee", "charged", "network", "msisdn", "reference")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def get(key, payout_id, detail):
    """GET-only, URL constructed from a UUID; redirects rejected, bodies bounded."""
    canonical = str(UUID(payout_id))
    path = ("/transactions/" if detail else "/payouts/") + canonical
    path += "/" if detail else "/verify/"
    req = Request(ROOT + path, method="GET", headers={
        "Authorization": "Bearer " + key, "Accept": "application/json"})
    with build_opener(NoRedirect()).open(req, timeout=15) as response:
        if response.status != 200:
            raise ValueError("Statut HTTP inattendu")
        raw = response.read(MAX_BYTES + 1)
        if len(raw) > MAX_BYTES:
            raise ValueError("Reponse SasPay trop volumineuse")
        payload = json.loads(raw.decode("utf-8"), parse_float=Decimal)
        if not isinstance(payload, dict):
            raise ValueError("Objet SasPay requis")
        if "success" in payload:
            if payload["success"] is not True or not isinstance(payload.get("data"), dict):
                raise ValueError("Enveloppe SasPay invalide")
            payload = payload["data"]
        return payload


def safe_enum(data, name):
    value = data.get(name)
    if not isinstance(value, str):
        return "ABSENT" if value is None else "TYPE_INVALIDE"
    return value if re.fullmatch(r"[a-zA-Z_][a-zA-Z0-9_.-]{0,39}", value) else "VALEUR_NON_CONFORME"


def safe_money(data, name):
    value = data.get(name)
    if isinstance(value, bool) or not isinstance(value, (str, int, Decimal)):
        return None
    literal = str(value)
    if not re.fullmatch(r"[0-9]{1,14}(?:\.[0-9]{1,6})?", literal):
        return None
    try:
        amount = Decimal(literal)
        return amount if amount.is_finite() else None
    except InvalidOperation:
        return None


def same_uuid(value, expected):
    try:
        return UUID(str(value)) == UUID(str(expected))
    except (ValueError, TypeError, AttributeError):
        return False


def report(data, payout_id, merchant_id, expected_amount, expected_fee):
    """Safe comparisons; never returns provider objects or personally identifying values."""
    if not isinstance(data, dict):
        raise ValueError("Objet financier attendu")
    status = safe_enum(data, "status")
    results = {
        "id_transaction": same_uuid(data.get("id"), payout_id),
        "compte_marchand": same_uuid(data.get("merchant"), merchant_id),
        "type_SORTIE_SASPAY": data.get("transaction_type") in ("RETRAIT", "TRANSFERT"),
        "sens_OUTBOUND": data.get("flow_direction") == "OUTBOUND",
        "devise_XOF": data.get("currency") == "XOF",
        "mode_DEDUCTED": data.get("fee_charge_mode") == "DEDUCTED",
    }
    requested = safe_money(data, "requested_amount")
    fee = safe_money(data, "client_fee")
    charged = safe_money(data, "debited_amount")
    net = safe_money(data, "net_amount")
    results.update({
        "montant_demande": requested == expected_amount,
        "frais_attendus": fee == expected_fee,
        "montant_debite": charged == expected_amount,
        "net_attendu": net == expected_amount - expected_fee,
        "identite_financiere": None not in (requested, fee, charged, net)
        and requested == charged == expected_amount and net == requested - fee,
    })
    fields = [x for x in FIELDS + ALIASES if x in data]
    # Field names are predefined; unknown names and actual values never leave this process.
    return {"status": status, "presence": fields,
            "type": safe_enum(data, "transaction_type"),
            "sens": safe_enum(data, "flow_direction"),
            "devise": safe_enum(data, "currency"),
            "mode": safe_enum(data, "fee_charge_mode"),
            "checks": results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", default="Backend/.env")
    parser.add_argument("--payout-id", required=True, help="UUID transaction SasPay existante")
    parser.add_argument("--expected-amount-xof", type=int, default=1000)
    parser.add_argument("--expected-fee-xof", type=int, default=37)
    args = parser.parse_args()
    try:
        payout_id = str(UUID(args.payout_id))
        if args.expected_amount_xof <= 0 or args.expected_fee_xof < 0 \
                or args.expected_fee_xof >= args.expected_amount_xof:
            raise ValueError()
    except ValueError:
        parser.error("Identifiant ou montants attendus invalides")
    settings = dict(load_env_file(args.env_file))
    settings.update(os.environ)
    key = settings.get("SASPAY_API_KEY", "")
    if not key.startswith(("sk_live_", "sk_test_")):
        parser.error("Cle SasPay absente ou invalide (valeur masquee)")
    try:
        merchant = str(UUID(settings.get("SASPAY_MERCHANT_ID", "")))
    except ValueError:
        parser.error("Compte marchand SasPay absent ou invalide (valeur masquee)")
    print("Diagnostic SasPay READ ONLY / aucune mutation / aucun POST")
    print("Environnement cle : " + ("LIVE" if key.startswith("sk_live_") else "TEST"))
    result_ok = True
    for detail, title in ((False, "VERIFY"), (True, "DETAILS")):
        print("\n" + title + " :")
        try:
            data = get(key, payout_id, detail=detail)
            result = report(
                data, payout_id, merchant, Decimal(args.expected_amount_xof),
                Decimal(args.expected_fee_xof))
            print("  statut : " + result["status"])
            for item in ("type", "sens", "devise", "mode"):
                print("  " + item + " : " + result[item])
            print("  champs presents (liste autorisee) : " + ", ".join(result["presence"]))
            for field, passed in result["checks"].items():
                print("  " + field + " : " + ("OK" if passed else "ECHEC"))
            if not all(result["checks"].values()):
                result_ok = False
        except HTTPError as exc:
            print("  GET refuse : HTTP " + str(exc.code))
            result_ok = False
        except (URLError, OSError, ValueError, json.JSONDecodeError):
            print("  GET impossible ou reponse invalide")
            result_ok = False
    print("\nAucun transfert, ecriture SQL ou changement de statut n'a ete effectue.")
    print("Ces verifications ne remplacent pas la reconciliation authentifiee Groupia.")
    return 0 if result_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
