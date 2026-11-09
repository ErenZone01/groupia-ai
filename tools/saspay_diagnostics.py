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
        # JSON decimal numbers must retain their exact value, never binary-float rounding.
        return json.load(reply, parse_float=Decimal)


def _money(value):
    """Parse textual decimals strictly and JSON numeric decimals by exact value."""
    if isinstance(value, bool) or not isinstance(value, (str, int, Decimal)):
        raise ValueError("invalid decimal rate")
    if isinstance(value, str):
        # Strings must use the documented fixed-point representation.
        if not re.fullmatch(r"[0-9]{1,14}(?:\.[0-9]{1,6})?", value):
            raise ValueError("invalid decimal rate")
        result = Decimal(value)
    else:
        # Exponent notation such as 0E-12 is legitimate for JSON numbers,
        # provided the value is within the original range and precision bounds.
        result = Decimal(value)
        if result < 0 or result >= Decimal("100000000000000"):
            raise ValueError("invalid decimal rate")
        if result.normalize().as_tuple().exponent < -6:
            raise ValueError("invalid decimal rate")
    return result

def _tariff_field(tier, field, *, nullable=False):
    """Identify an incompatible field without revealing confidential rate values."""
    if not isinstance(tier, dict):
        raise ValueError("palier non structuré comme un objet JSON")
    if field not in tier:
        raise ValueError("champ tarifaire '" + field + "' absent")
    value = tier[field]
    if value is None and nullable:
        return None
    if isinstance(value, bool) or not isinstance(value, (str, int, Decimal)):
        typename = (
            "nombre flottant non exact" if isinstance(value, float)
            else type(value).__name__
        )
        raise ValueError("champ '" + field + "' de type " + typename + " non accepté")
    try:
        return _money(value)
    except (ValueError, InvalidOperation):
        raise ValueError("champ '" + field + "' : format décimal non reconnu") from None


def quote_reason(rate, amount, max_total):
    """Classify a network locally. Never initiates payment or guesses rounding."""
    payin = rate.get("payin", {})
    if not isinstance(payin, dict) or payin.get("available") is not True:
        return "encaissement indisponible"
    if payin.get("otp_required") is not False:
        return "OTP ou autorisation non compatible avec le parcours actuel"
    tiers = payin.get("tiers")
    if not isinstance(tiers, list):
        return "champ 'payin.tiers' absent ou non structuré en liste"
    try:
        matched = []
        for tier in tiers:
            low = _tariff_field(tier, "min_amount")
            high = _tariff_field(tier, "max_amount", nullable=True)
            if amount >= low and (high is None or amount <= high):
                matched.append(tier)
        if len(matched) != 1:
            return "aucun palier tarifaire unique pour ce montant"
        tier = matched[0]
        if tier.get("currency") != "XOF" or tier.get("fee_charge_mode") != "ADD_ON":
            return "frais à la charge du client (ADD_ON) non confirmés"
        fee = (amount * _tariff_field(tier, "percent") / Decimal("100")
               + _tariff_field(tier, "fixed"))
        floor = _tariff_field(tier, "floor_amount", nullable=True)
        cap = _tariff_field(tier, "cap_amount", nullable=True)
        if floor is not None and cap is not None and floor > cap:
            return "plancher tarifaire supérieur au plafond de frais"
        if floor is not None:
            fee = max(fee, floor)
        if cap is not None:
            fee = min(fee, cap)
        if fee != fee.to_integral_value():
            return "frais fractionnaires XOF : règle d'arrondi non confirmée"
        if amount + fee > max_total:
            return "total frais inclus supérieur au plafond pilote"
        return "devis théoriquement compatible avec les règles locales"
    except ValueError as e:
        return str(e)
    except (TypeError, InvalidOperation, KeyError, AttributeError):
        return "tarif incomplet ou structure incohérente"


def compatible_amounts(rate, reference_amount, max_total, limit=3):
    """Find sample integer XOF order amounts. Read-only and never promises bookability."""
    if not isinstance(rate, dict) or max_total <= 0:
        return []
    code, name = rate.get("network_code"), rate.get("network_name")
    if not isinstance(code, str) or not re.fullmatch(r"[a-z0-9_]{1,64}", code):
        return []
    if not isinstance(name, str) or not name.strip() or len(name) > 120:
        return []
    # Keep exploration inside the existing pilot ceiling including calculated fees.
    upper = min(5000, int(max_total))
    if upper < 200:
        return []
    possible = []
    for amount in range(200, upper + 1):
        reason = quote_reason(rate, Decimal(amount), max_total)
        if reason.startswith("devis théoriquement compatible"):
            possible.append(amount)
    return sorted(possible, key=lambda amount: (abs(Decimal(amount) - reference_amount), amount))[:limit]

def print_quote_diagnostic(rates, amount, max_total, suggest_amounts=False):
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
        name = rate.get("network_name")
        if not re.fullmatch(r"[a-z0-9_]{1,64}", label):
            reason = "network_code absent ou invalide : rejet côté Java"
        elif not isinstance(name, str) or not name.strip() or len(name) > 120:
            reason = "network_name absent ou invalide : réseau ignoré côté Java"
        else:
            reason = quote_reason(rate, amount, max_total)
        print("  " + label + ": " + reason)
        if suggest_amounts and reason != "devis théoriquement compatible avec les règles locales":
            examples = compatible_amounts(rate, amount, max_total)
            if examples:
                print("    Montants d'exemple théoriquement compatibles (XOF) : " +
                      ", ".join(map(str, examples)))
            else:
                print("    Aucun montant compatible trouvé entre 200 XOF et le plafond pilote.")


def diagnose(env: dict[str, str], amount=None, organization_id=None,
             suggest_amounts=False):
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
                print_quote_diagnostic(rates, Decimal(amount), Decimal(raw_cap),
                                       suggest_amounts=suggest_amounts)
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
    parser.add_argument("--suggest-amounts", action="store_true",
                        help="Cherche seulement des montants fictifs éligibles par réseau (lecture seule)")
    args = parser.parse_args()
    if args.amount_xof is not None and args.amount_xof <= 0:
        parser.error("--amount-xof doit être positif")
    variables = dict(load_env_file(args.env_file)) if args.env_file else {}
    variables.update(os.environ)
    if args.suggest_amounts and args.amount_xof is None:
        parser.error("--suggest-amounts nécessite --amount-xof")
    raise SystemExit(diagnose(variables, args.amount_xof, args.organization_id,
                              args.suggest_amounts))
