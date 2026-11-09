"""Read-only SasPay diagnostic. No payment/transfer POST and no credential logging.

Example:
  python tools/saspay_diagnostics.py --env-file Backend/.env
Only the configured API hostname is contacted, using GET requests.
"""
from __future__ import annotations
import argparse
import json
import os
import re
from decimal import Decimal, InvalidOperation
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import UUID

BASE = "https://api.saspay.me/api/v1"
PATHS = ("/merchant-balances/", "/pricing/my-rates/")


def load_env_file(path: str) -> dict[str, str]:
    values = {}
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        item = line.strip()
        if not item or item.startswith("#") or "=" not in item:
            continue
        key, value = item.split("=", 1)
        key = key.strip()
        if key.startswith("export "):
            key = key[7:].strip()
        if not key.isidentifier():
            continue
        values[key] = value.strip().strip('"').strip("'")
    return values


def data_rows(raw):
    if isinstance(raw, dict) and "success" in raw:
        if raw["success"] is not True:
            raise ValueError("SasPay reported an unsuccessful response")
        raw = raw.get("data")
    if isinstance(raw, dict):
        raw = raw.get("results", raw.get("data", []))
    if isinstance(raw, list):
        return raw
    raise ValueError("SasPay response format not recognized")


def read_only_get(path: str, api_key: str):
    if path not in PATHS:
        raise ValueError("Only approved read-only paths are supported")
    request = Request(
        BASE + path,
        headers={"Authorization": "Bearer " + api_key, "Accept": "application/json"},
        method="GET",
    )
    with urlopen(request, timeout=12) as reply:
        return json.load(reply)


def _money(value):
    """Mimic the Java quote parser: amounts must be decimal strings."""
    if not isinstance(value, str) or not re.fullmatch(r"[0-9]{1,14}(?:\.[0-9]{1,6})?", value):
        raise ValueError("invalid decimal rate")
    return Decimal(value)


def quote_reason(rate, amount, max_total):
    """Classify a network locally. Never initiates payment or guesses rounding."""
    payin = rate.get("payin", {})
    if not isinstance(payin, dict) or payin.get("available") is not True:
        return "encaissement indisponible"
    if payin.get("otp_required") is not False:
        return "OTP ou autorisation non compatible avec le parcours actuel"
    tiers = payin.get("tiers")
    if not isinstance(tiers, list):
        return "tarifs incomplets"
    try:
        matched = []
        for tier in tiers:
            low = _money(tier["min_amount"])
            high = tier.get("max_amount")
            high = _money(high) if high is not None else None
            if amount >= low and (high is None or amount <= high):
                matched.append(tier)
        if len(matched) != 1:
            return "aucun palier tarifaire unique pour ce montant"
        tier = matched[0]
        if tier.get("currency") != "XOF" or tier.get("fee_charge_mode") != "ADD_ON":
            return "frais à la charge du client (ADD_ON) non confirmés"
        fee = amount * _money(tier["percent"]) / Decimal("100") + _money(tier["fixed"])
        floor, cap = tier.get("floor_amount"), tier.get("cap_amount")
        if floor is not None:
            fee = max(fee, _money(floor))
        if cap is not None:
            fee = min(fee, _money(cap))
        if fee != fee.to_integral_value():
            return "frais fractionnaires XOF : règle d'arrondi non confirmée"
        if amount + fee > max_total:
            return "total frais inclus supérieur au plafond pilote"
        return "devis théoriquement compatible avec les règles locales"
    except (ValueError, TypeError, InvalidOperation, KeyError, AttributeError):
        return "tarif incomplet ou format non accepté par Groupia"


def print_quote_diagnostic(rates, amount, max_total):
    if amount <= 0:
        raise ValueError("invalid amount")
    print(f"Analyse locale et indicative : commande {amount} XOF, plafond {max_total} XOF.")
    print("Cette analyse ne remplace pas le devis serveur et ne déclenche aucun paiement.")
    networks = [r for r in rates if isinstance(r, dict) and r.get("country_code") == "SN"
                and r.get("currency") == "XOF"]
    if not networks:
        print("Aucun tarif SN/XOF retourné par SasPay.")
    for rate in networks:
        label = str(rate.get("network_code") or "unknown")
        if not re.fullmatch(r"[a-z0-9_]{1,64}", label):
            label = "reseau_non_reconnu"
        print("  " + label + ": " + quote_reason(rate, amount, max_total))


def diagnose(env: dict[str, str], amount=None, organization_id=None):
    key = env.get("SASPAY_API_KEY", "")
    if not (key.startswith("sk_live_") or key.startswith("sk_test_")):
        print("SASPAY_API_KEY manquante ou de format inconnu.")
        return 1
    print("Diagnostic SasPay en lecture seule : aucun paiement, aucun transfert.")
    if organization_id:
        try:
            actual = UUID(organization_id)
            authorized = UUID(env.get("SASPAY_LIVE_PILOT_ORGANIZATION_ID", ""))
            print("Boutique Groupia autorisée pour le pilote : " +
                  ("OUI" if authorized == actual else "NON"))
        except ValueError:
            print("Boutique pilote : identifiant de configuration absent ou invalide.")
    try:
        balances = data_rows(read_only_get("/merchant-balances/", key))
        merchants = sorted({
            str(UUID(row["merchant"]))
            for row in balances
            if isinstance(row, dict) and row.get("merchant")
        })
        if len(merchants) == 1:
            print("SASPAY_MERCHANT_ID=" + merchants[0])
        elif len(merchants) > 1:
            print("Plusieurs identifiants marchands observés; confirmer le bon compte:")
            for item in merchants:
                print("  " + item)
        else:
            print("Aucun merchant_id trouvé dans les soldes; contacter SasPay.")
        rates = data_rows(read_only_get("/pricing/my-rates/", key))
        senegal = [
            r.get("network_code", "")
            for r in rates
            if isinstance(r, dict) and r.get("country_code") == "SN"
            and r.get("currency") == "XOF"
            and isinstance(r.get("payin"), dict)
            and r["payin"].get("available") is True
        ]
        print("Réseaux SN/XOF actifs annoncés : " + (", ".join(sorted(set(senegal))) or "aucun"))
        if amount is not None:
            raw_cap = env.get("SASPAY_LIVE_PILOT_MAX_CHARGED_XOF", "2000")
            if not raw_cap.isdigit() or not 1 <= int(raw_cap) <= 5000:
                print("Plafond pilote absent ou invalide : refus de simuler un devis.")
            else:
                print_quote_diagnostic(rates, Decimal(amount), Decimal(raw_cap))
        if not senegal:
            print("Aucun tarif pay-in compatible constaté; ne pas activer les paiements.")
        return 0
    except HTTPError as e:
        print("Requête SasPay refusée (HTTP " + str(e.code) + "). Vérifier la clé et ses droits.")
    except (URLError, TimeoutError, OSError):
        print("SasPay injoignable; vérifier la connexion HTTPS.")
    except (ValueError, KeyError, json.JSONDecodeError):
        print("Réponse SasPay inattendue; aucune opération financière n'a été exécutée.")
    return 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", help="Chemin local du fichier .env (jamais affiché)")
    parser.add_argument("--amount-xof", type=int, help="Montant de commande, sans frais")
    parser.add_argument("--organization-id", help="UUID Groupia de la boutique test")
    args = parser.parse_args()
    if args.amount_xof is not None and args.amount_xof <= 0:
        parser.error("--amount-xof doit être positif")
    variables = dict(load_env_file(args.env_file)) if args.env_file else {}
    variables.update(os.environ)
    raise SystemExit(diagnose(variables, args.amount_xof, args.organization_id))
