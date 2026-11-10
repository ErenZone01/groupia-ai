"""Read-only SasPay pay-in verification for a previously initiated transaction.

This CLI never creates, retries, cancels, refunds or pays out anything.
It prints only the payment status and financial comparisons; no customer PII.
"""
import argparse
from decimal import Decimal, InvalidOperation
from datetime import datetime, timedelta, timezone
import json
import os
import re
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from urllib.parse import urlencode
from uuid import UUID

from saspay_diagnostics import load_env_file

ROOT = "https://api.saspay.me/api/v1"


def money(obj, name):
    value = obj.get(name)
    if isinstance(value, bool) or not isinstance(value, (str, int, Decimal)):
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
    if "success" in payload and payload["success"] is not True:
        raise ValueError("Enveloppe SasPay de succès invalide")
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
        return json.loads(raw.decode("utf-8"), parse_float=Decimal)


def fetch_transaction_detail(key, payment_id):
    """Separate read-only transaction resource; not gateway verification."""
    payment_id = str(UUID(payment_id))
    req = Request(ROOT + "/transactions/" + payment_id + "/",
                  headers={"Authorization": "Bearer " + key, "Accept": "application/json"},
                  method="GET")
    with urlopen(req, timeout=12) as res:
        raw = res.read(262145)
        if len(raw) > 262144:
            raise ValueError("Réponse du fournisseur trop volumineuse")
        return json.loads(raw.decode("utf-8"), parse_float=Decimal)


def fetch_transaction_page(key):
    """Read-only first page to detect ID or credential mismatches, not reconcile payments."""
    query = urlencode({
        "transaction_type": "PAIEMENT",
        "flow_direction": "INBOUND",
        "currency": "XOF",
        "page_size": 50,
    })
    req = Request(ROOT + "/transactions/?" + query,
                  headers={"Authorization": "Bearer " + key, "Accept": "application/json"},
                  method="GET")
    with urlopen(req, timeout=12) as res:
        raw = res.read(262145)
        if len(raw) > 262144:
            raise ValueError("Réponse du fournisseur trop volumineuse")
        return json.loads(raw.decode("utf-8"), parse_float=Decimal)


def _get_safe(key, path):
    """Use only preconstructed, allowlisted SasPay GET paths; never return raw data to CLI."""
    if not path.startswith(("/merchant-balances/?", "/transactions/?")):
        raise ValueError("Chemin SasPay interdit")
    req = Request(ROOT + path,
                  headers={"Authorization": "Bearer " + key,
                           "Accept": "application/json"},
                  method="GET")
    with urlopen(req, timeout=12) as res:
        raw = res.read(262145)
        if len(raw) > 262144:
            raise ValueError("Réponse du fournisseur trop volumineuse")
        return json.loads(raw.decode("utf-8"), parse_float=Decimal)


def audit_merchant_scope(key, expected_merchant, around_utc):
    """Read-only visibility audit: account wallets and nearby pay-in count, no customer data."""
    print("Contrôle de visibilité marchande via la clé LIVE (GET uniquement) :")
    try:
        wallet_page = _get_safe(key, "/merchant-balances/?page_size=100")
        wallets = wallet_page.get("results") if isinstance(wallet_page, dict) else None
        if not isinstance(wallets, list):
            print("  Wallets : structure de réponse inconnue")
        else:
            merchant_set = {
                str(row.get("merchant", "")).lower()
                for row in wallets if isinstance(row, dict)
            }
            print("  Wallets (première page) : " + str(len(wallets)))
            print("  Compte marchand configuré présent : " +
                  ("OUI" if expected_merchant.lower() in merchant_set else "NON/INCONNU"))
            if wallet_page.get("next"):
                print("  Wallets paginés : absence sur la première page non concluante.")
    except HTTPError as exc:
        print("  Liste des wallets : HTTP " + str(exc.code))
    except (URLError, OSError, ValueError, json.JSONDecodeError):
        print("  Liste des wallets : non exploitable")

    if around_utc is None:
        print("  Fenêtre de transactions non demandée ; utiliser --created-around-utc.")
        return

    lower = (around_utc - timedelta(minutes=10)).strftime("%Y-%m-%dT%H:%M:%SZ")
    upper = (around_utc + timedelta(minutes=10)).strftime("%Y-%m-%dT%H:%M:%SZ")
    query = urlencode({
        "transaction_type": "PAIEMENT",
        "flow_direction": "INBOUND",
        "currency": "XOF",
        "created_at__gte": lower,
        "created_at__lte": upper,
        "page_size": 200,
    })
    try:
        page = _get_safe(key, "/transactions/?" + query)
        rows = page.get("results") if isinstance(page, dict) else None
        if not isinstance(rows, list):
            print("  Transactions datées : structure de réponse inconnue")
            return
        matching_merchant = [
            row for row in rows if isinstance(row, dict)
            and str(row.get("merchant", "")).lower() == expected_merchant.lower()
        ]
        print("  Transactions pay-in XOF dans la fenêtre : " + str(len(rows)))
        print("  Transactions du marchand Groupia configuré : " + str(len(matching_merchant)))
        print("  Autres marchand(s) dans la fenêtre : " +
              ("OUI" if len(rows) > len(matching_merchant) else "NON"))
        if page.get("next"):
            print("  Résultats paginés : la fenêtre n'est pas exhaustive.")
        print("  Un statut dans la liste n'est PAS une preuve de paiement.")
    except HTTPError as exc:
        print("  Recherche datée : HTTP " + str(exc.code))
    except (URLError, OSError, ValueError, json.JSONDecodeError):
        print("  Recherche datée : réponse non exploitable")


def diagnose_verify_404(key, payment_id, merchant, audit_scope=False, around_utc=None):
    """All requests are GET; no monetary decision can be made from transaction listing/detail."""
    payment_id = str(UUID(payment_id))
    print("404 sur /payments/{id}/verify/ : transaction NON VÉRIFIÉE.")
    try:
        record = fetch_transaction_detail(key, payment_id)
        if "data" in record and isinstance(record["data"], dict):
            record = record["data"]
        if not isinstance(record, dict) or str(record.get("id", "")).replace("-", "").lower() != UUID(payment_id).hex:
            print("Détail transaction : référence incohérente.")
        elif str(record.get("merchant", "")).lower() != merchant.lower():
            print("Détail transaction : compte marchand différent de la configuration.")
        else:
            status = record.get("status")
            print("Détail transaction : identifiant accessible par la clé active.")
            print("Statut indicatif : " + (status if status in ("PENDING", "FAILED", "SUCCESS") else "INCONNU"))
            print("Ce détail ne remplace PAS /payments/{id}/verify/.")
    except HTTPError as exc:
        print("Détail /transactions/{id}/ : HTTP " + str(exc.code))
    except (URLError, OSError, ValueError, json.JSONDecodeError):
        print("Détail transaction inaccessible ou réponse non exploitable.")

    try:
        page = fetch_transaction_page(key)
        if isinstance(page, dict):
            rows = page.get("results", [])
            if isinstance(rows, list):
                matches = [row for row in rows
                           if isinstance(row, dict) and isinstance(row.get("id"), str)
                           and row["id"].replace("-", "").lower() == UUID(payment_id).hex]
                print("Liste transactions (première page, max. 50) : " +
                      ("identifiant trouvé" if matches else "identifiant non trouvé dans cette page"))
                if matches:
                    own = any(str(row.get("merchant", "")).lower() == merchant.lower()
                              for row in matches)
                    print("Identifiant lié au marchand configuré : " +
                          ("OUI" if own else "NON"))
                if page.get("next"):
                    print("D'autres pages existent ; absence sur cette page non concluante.")
            else:
                print("Liste transactions : structure inconnue.")
        else:
            print("Liste transactions : réponse non structurée.")
    except HTTPError as exc:
        print("Liste /transactions/ : HTTP " + str(exc.code))
    except (URLError, OSError, ValueError, json.JSONDecodeError):
        print("Liste transactions indisponible ou réponse non exploitable.")
    if audit_scope:
        audit_merchant_scope(key, merchant, around_utc)
    print("AUCUN rapprochement en base, aucun nouvel encaissement, aucune confirmation.")
    return 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", default="Backend/.env")
    parser.add_argument("--payment-id", required=True, help="UUID de transaction SasPay existante")
    parser.add_argument("--expected-amount-xof", type=int, required=True)
    parser.add_argument("--audit-scope", action="store_true",
                        help="Contrôle GET-only du marchand et des transactions accessibles à la clé")
    parser.add_argument("--created-around-utc",
                        help="Heure UTC de l'incident (YYYY-MM-DDTHH:MM:SSZ), fenêtre +/- 10 min")
    args = parser.parse_args()
    around_utc = None
    if args.created_around_utc:
        if not args.audit_scope:
            parser.error("--created-around-utc exige --audit-scope")
        try:
            around_utc = datetime.strptime(
                args.created_around_utc, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
        except ValueError:
            parser.error("--created-around-utc doit être YYYY-MM-DDTHH:MM:SSZ")
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
    print("Clé active : " +
          ("LIVE" if key.startswith("sk_live_") else "TEST") +
          "; source : " +
          ("variables du processus" if os.environ.get("SASPAY_API_KEY") is not None
           else args.env_file) +
          " (valeur masquée)")
    try:
        merchant = str(UUID(merchant))
        cap = Decimal(env.get("SASPAY_LIVE_PILOT_MAX_CHARGED_XOF", "2000"))
        if cap <= 0 or cap > Decimal("5000"):
            raise ValueError("Plafond pilote non autorisé")
        data = fetch_payment(key, args.payment_id)
        result = safe_summary(data, merchant, Decimal(args.expected_amount_xof), cap)
    except HTTPError as exc:
        if exc.code == 404:
            return diagnose_verify_404(
                key, args.payment_id, merchant, args.audit_scope, around_utc)
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
