"""Read-only, privacy-safe audit of an existing SasPay hosted checkout session.

Never creates, retries, cancels, refunds, or settles a payment.
Prints no checkout URL, payment link token, customer details or credentials.
"""
import argparse
from decimal import Decimal, InvalidOperation
import json
import os
import re
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from uuid import UUID

from saspay_diagnostics import load_env_file

ROOT = "https://api.saspay.me/api/v1"
MAX_BYTES = 262144
SAFE_HOST = re.compile(r"[a-z0-9]+(?:[.-][a-z0-9]+)*\.[a-z]{2,}")


def unwrap(value):
    if not isinstance(value, dict):
        raise ValueError("Réponse non structurée")
    if "success" in value:
        if value["success"] is not True or not isinstance(value.get("data"), dict):
            raise ValueError("Enveloppe SasPay invalide")
        value = value["data"]
    return value


def get_session(key, session_id, status=False):
    """Only fixed SasPay GET routes, with a validated canonical UUID."""
    uid = str(UUID(session_id))
    path = f"/checkout-sessions/{uid}/"
    if status:
        path += "status/"
    request = Request(
        ROOT + path,
        headers={"Authorization": "Bearer " + key, "Accept": "application/json"},
        method="GET",
    )
    with urlopen(request, timeout=12) as response:
        raw = response.read(MAX_BYTES + 1)
        if len(raw) > MAX_BYTES:
            raise ValueError("Réponse SasPay trop volumineuse")
        return unwrap(json.loads(raw.decode("utf-8"), parse_float=Decimal))


def hosted_url_host(value):
    """Show the domain only; never reveal URL paths, query tokens, userinfo or PII."""
    if not isinstance(value, str) or len(value) > 4096 or not value:
        return None, False
    try:
        url = urlsplit(value)
        name = url.hostname
        is_safe = (
            url.scheme == "https"
            and name is not None
            and bool(SAFE_HOST.fullmatch(name))
            and url.username is None
            and url.password is None
            and url.fragment == ""
            and url.port in (None, 443)
        )
        return (name if is_safe else None), is_safe
    except ValueError:
        return None, False


def summary(detail, state, session_id, payment_attempt_id, merchant_id, expected_amount):
    expected_session = UUID(session_id)
    try:
        ids_match = UUID(str(detail.get("id"))) == expected_session and (
            UUID(str(state.get("id"))) == expected_session
        )
    except (ValueError, TypeError, AttributeError):
        ids_match = False
    metadata = detail.get("metadata")
    metadata = metadata if isinstance(metadata, dict) else {}
    merchant_ok = str(detail.get("merchant", "")).lower() == merchant_id.lower()
    attempt_ok = metadata.get("groupia_payment_attempt_id") == payment_attempt_id
    try:
        amount = Decimal(str(detail.get("amount")))
        amount_ok = amount == expected_amount and amount.is_finite()
    except (InvalidOperation, TypeError, ValueError):
        amount_ok = False
    currency_ok = detail.get("currency") == "XOF"
    host, url_safe = hosted_url_host(detail.get("checkout_url"))
    status = state.get("status")
    if status not in ("PENDING", "PAID", "CANCELLED", "EXPIRED"):
        status = "INCONNU"
    checks = {
        "ID de session": ids_match,
        "Compte marchand": merchant_ok,
        "Tentative Groupia": attempt_ok,
        "Montant attendu": amount_ok,
        "Devise XOF": currency_ok,
    }
    return {
        "status": status,
        "checks": checks,
        "host": host,
        "url_safe": url_safe,
        "url_present": isinstance(detail.get("checkout_url"), str)
        and bool(detail["checkout_url"]),
        "usable": status == "PENDING" and all(checks.values()) and url_safe,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", default="Backend/.env")
    parser.add_argument("--session-id", required=True, help="UUID de session SasPay déjà créée")
    parser.add_argument("--attempt-id", required=True, help="UUID de tentative Groupia existante")
    parser.add_argument("--expected-amount-xof", type=int, required=True)
    args = parser.parse_args()

    try:
        session_id = str(UUID(args.session_id))
        attempt_id = str(UUID(args.attempt_id))
    except ValueError:
        parser.error("UUID de session ou tentative invalide")
    if not 0 < args.expected_amount_xof <= 5000:
        parser.error("Montant pilote attendu hors limites")

    settings = dict(load_env_file(args.env_file))
    settings.update(os.environ)
    key = settings.get("SASPAY_API_KEY", "")
    try:
        merchant = str(UUID(settings.get("SASPAY_MERCHANT_ID", "")))
    except ValueError:
        parser.error("SASPAY_MERCHANT_ID absent ou invalide")
    if not key.startswith(("sk_live_", "sk_test_")):
        parser.error("Clé SasPay absente ou invalide")
    print(
        "Clé active : " + ("LIVE" if key.startswith("sk_live_") else "TEST")
        + " ; source : " + (
            "variables du processus" if "SASPAY_API_KEY" in os.environ else args.env_file
        )
        + " ; valeurs masquées"
    )

    try:
        detail = get_session(key, session_id)
        state = get_session(key, session_id, status=True)
        result = summary(
            detail, state, session_id, attempt_id, merchant, Decimal(args.expected_amount_xof)
        )
    except HTTPError as err:
        print("Consultation de session refusée (HTTP " + str(err.code) + ").")
        return 1
    except (URLError, OSError, ValueError, InvalidOperation, json.JSONDecodeError):
        print("Consultation impossible ou réponse non exploitable.")
        return 1

    print("Statut de session : " + result["status"] + " (ne prouve aucun règlement)")
    for name, passed in result["checks"].items():
        print("  " + name + " : " + ("OK" if passed else "ÉCHEC"))
    print("Checkout URL présente : " + ("OUI" if result["url_present"] else "NON"))
    print("Hôte checkout HTTPS : " + (result["host"] or "ABSENT / NON CONFORME"))
    allowed = set(
        s.strip() for s in settings.get("SASPAY_CHECKOUT_HOSTS", "").split(",") if s.strip()
    )
    print("Hôte dans SASPAY_CHECKOUT_HOSTS : "
          + ("OUI" if result["host"] in allowed else "NON / INCONNU"))
    print("Session ouvrable sous conditions Groupia : "
          + ("OUI, après réconciliation serveur" if result["usable"]
             and result["host"] in allowed else "NON"))
    print("AUCUN POST, règlement, modification DB ou confirmation n'a été effectué.")
    return 0 if all(result["checks"].values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
