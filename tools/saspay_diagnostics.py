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
from urllib.parse import urlsplit

BASE = "https://api.saspay.me/api/v1"
PATHS = ("/merchant-balances/", "/pricing/my-rates/",
         "/merchant-webhooks/", "/merchant-webhook-subscriptions/")


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


def quote_reason(rate, amount, max_total, allow_fractional=False):
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
        if fee.normalize().as_tuple().exponent < -2:
            return "précision des frais supérieure à deux décimales : devis refusé"
        if not allow_fractional and fee != fee.to_integral_value():
            return "frais fractionnaires XOF : option pilote non activée"
        if amount + fee > max_total:
            return "total frais inclus supérieur au plafond pilote"
        return "devis théoriquement compatible avec les règles locales"
    except ValueError as e:
        return str(e)
    except (TypeError, InvalidOperation, KeyError, AttributeError):
        return "tarif incomplet ou structure incohérente"


def compatible_amounts(rate, reference_amount, max_total, limit=3, allow_fractional=False):
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
        reason = quote_reason(rate, Decimal(amount), max_total, allow_fractional)
        if reason.startswith("devis théoriquement compatible"):
            possible.append(amount)
    return sorted(possible, key=lambda amount: (abs(Decimal(amount) - reference_amount), amount))[:limit]

def print_quote_diagnostic(rates, amount, max_total, suggest_amounts=False,
                           allow_fractional=False):
    if amount <= 0:
        raise ValueError("invalid amount")
    print(f"Analyse locale et indicative : commande {amount} XOF, plafond {max_total} XOF.")
    print("Cette analyse ne remplace pas le devis serveur et ne déclenche aucun paiement.")
    print("Frais décimaux XOF SasPay (deux décimales exactes) : " +
          ("ACTIVÉS EN PILOTE" if allow_fractional else "DÉSACTIVÉS"))
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
            reason = quote_reason(rate, amount, max_total, allow_fractional)
        print("  " + label + ": " + reason)
        if suggest_amounts and reason != "devis théoriquement compatible avec les règles locales":
            examples = compatible_amounts(rate, amount, max_total,
                                          allow_fractional=allow_fractional)
            if examples:
                print("    Montants d'exemple théoriquement compatibles (XOF) : " +
                      ", ".join(map(str, examples)))
            else:
                print("    Aucun montant compatible trouvé entre 200 XOF et le plafond pilote.")



def payout_quote_reason(rate, amount):
    """Read-only indicative quote; does not prove API scope, IP access or wallet liquidity."""
    if not isinstance(rate, dict) or rate.get("country_code") != "SN" or rate.get("currency") != "XOF":
        return "réseau hors Sénégal/XOF", None
    payout = rate.get("payout")
    if not isinstance(payout, dict) or payout.get("available") is not True:
        return "payout non disponible pour ce compte/réseau", None
    if payout.get("otp_required") is not False:
        return "OTP payout non confirmé pour ce parcours", None
    tiers = payout.get("tiers")
    if not isinstance(tiers, list):
        return "grille payout absente", None
    try:
        matching = []
        for tier in tiers:
            low = _tariff_field(tier, "min_amount")
            high = _tariff_field(tier, "max_amount", nullable=True)
            if amount >= low and (high is None or amount <= high):
                matching.append(tier)
        if len(matching) != 1:
            return "pas de palier payout unique au montant demandé", None
        tier = matching[0]
        if tier.get("currency") != "XOF":
            return "devise du palier payout non conforme à XOF", None
        mode = tier.get("fee_charge_mode")
        if mode != "DEDUCTED":
            known_mode = mode if mode in ("ADD_ON", "DEDUCTED") else "absent/inconnu"
            return ("DEDUCTED non confirmé (mode du tarif payout : " +
                    known_mode + ") ; demander confirmation à SasPay", None)
        fee = (amount * _tariff_field(tier, "percent") / Decimal("100")
               + _tariff_field(tier, "fixed"))
        floor = _tariff_field(tier, "floor_amount", nullable=True)
        cap = _tariff_field(tier, "cap_amount", nullable=True)
        if floor is not None and cap is not None and floor > cap:
            return "plancher payout supérieur au plafond", None
        if floor is not None:
            fee = max(fee, floor)
        if cap is not None:
            fee = min(fee, cap)
        if fee.normalize().as_tuple().exponent < -2:
            return "frais payout à plus de deux décimales", None
        if fee >= amount:
            return "frais payout supérieurs ou égaux au brut : net nul", None
        return "DEDUCTED compatible à titre indicatif", fee
    except (ValueError, InvalidOperation, TypeError, KeyError, AttributeError) as exc:
        return "grille payout non exploitable : " + (str(exc) if isinstance(exc, ValueError) else "format invalide"), None


def print_unconfigured_payout_candidates(rates, label, amount):
    """List tariff-code candidates ONLY; never authorize or configure withdrawals."""
    prefix = "wave_" if label == "Wave" else "orange_"
    candidates = [
        rate for rate in rates
        if isinstance(rate, dict) and rate.get("country_code") == "SN"
        and rate.get("currency") == "XOF"
        and isinstance(rate.get("network_code"), str)
        and re.fullmatch(r"[a-z0-9_]{3,64}", rate["network_code"])
        and rate["network_code"].startswith(prefix)
    ]
    if not candidates:
        print("    Aucun code candidat correspondant dans les tarifs SN/XOF du compte.")
        return
    codes = [item["network_code"] for item in candidates]
    for rate in sorted(candidates, key=lambda item: item["network_code"]):
        code = rate["network_code"]
        if codes.count(code) != 1:
            print("    " + code + " : code dupliqué, devis non fiable.")
            continue
        reason, fee = payout_quote_reason(rate, amount)
        print("    Candidat tarifaire " + code + " (non autorisé automatiquement) : " + reason)
        if fee is not None:
            print("      Estimation indicative : frais " + str(fee) +
                  " XOF ; net vendeur " + str(amount - fee) + " XOF.")
    print("    Un code candidat ne prouve ni la permission PAYOUT, ni l'IP autorisée,")
    print("    ni l'autorisation contractuelle de SasPay pour un retrait LIVE.")


def print_payout_diagnostic(rates, env, amount):
    """Print approved-code candidates without a payout POST or leaking credentials."""
    print("Pré-diagnostic retraits SasPay, lecture seule ; montant vendeur : " + str(amount) + " XOF.")
    print("Ni les droits API PAYOUT, ni l'IP whitelistée, ni les fonds disponibles ne sont prouvés par GET.")
    for label, variable in (("Wave", "SASPAY_PAYOUTS_WAVE_NETWORK"),
                            ("Orange Money", "SASPAY_PAYOUTS_ORANGE_NETWORK")):
        code = env.get(variable, "").strip()
        if not re.fullmatch(r"[a-z0-9_]{3,64}", code):
            print("  " + label + ": code réseau payout non configuré/invalidé.")
            print_unconfigured_payout_candidates(rates, label, amount)
            continue
        matches = [r for r in rates if isinstance(r, dict)
                   and r.get("country_code") == "SN" and r.get("currency") == "XOF"
                   and r.get("network_code") == code]
        if len(matches) != 1:
            print("  " + label + ": code absent ou non unique des tarifs SN/XOF du compte.")
            continue
        reason, fee = payout_quote_reason(matches[0], amount)
        print("  " + label + " (" + code + ") : " + reason)
        if fee is not None:
            print("    Estimation seulement : brut débité au wallet " + str(amount) +
                  " XOF, frais vendeur " + str(fee) + " XOF, net vendeur " +
                  str(amount - fee) + " XOF, commission Groupia 0 XOF.")
            print("    Le net final reste à rapprocher avec la transaction SasPay.")


def diagnose_webhooks(env, key):
    """GET-only webhook configuration overview; never fetches delivery payloads or signing secrets."""
    print("Webhook : secret configuré localement : " +
          ("OUI" if env.get("SASPAY_WEBHOOK_SECRET", "").strip() else "NON"))
    endpoints = data_rows(read_only_get("/merchant-webhooks/", key))
    subscriptions = data_rows(read_only_get("/merchant-webhook-subscriptions/", key))
    if not endpoints:
        print("Aucun point de réception webhook listé sur cette page du compte.")
    for hook in endpoints:
        if not isinstance(hook, dict):
            continue
        address = urlsplit(str(hook.get("url") or ""))
        # Never print URL queries or credentials; keep only scheme, hostname and path.
        host = address.hostname or "hôte-invalide"
        path = address.path or "/"
        if not re.fullmatch(r"[a-zA-Z0-9.-]{1,250}", host):
            host = "hôte-invalide"
        if not re.fullmatch(r"/[a-zA-Z0-9/_.-]{0,250}", path):
            path = "chemin-non-reconnu"
        matches_path = path == "/api/payment/webhooks/saspay"
        environment = hook.get("environment")
        enabled = hook.get("is_active") is True
        hook_id = hook.get("id")
        events = sorted({
            row["event_type"]
            for row in subscriptions
            if isinstance(row, dict) and row.get("webhook") == hook_id
            and isinstance(row.get("event_type"), str)
            and re.fullmatch(r"[a-z_]+(?:\.[a-z_]+)?", row["event_type"])
        })
        print("  Endpoint: HTTPS=" + ("OUI" if address.scheme == "https" else "NON") +
              ", hôte=" + host + ", chemin=" + path)
        print("    Environnement=" + (str(environment) if environment in ("LIVE", "TEST") else "INCONNU") +
              ", actif=" + ("OUI" if enabled else "NON") +
              ", chemin Groupia attendu=" + ("OUI" if matches_path else "NON"))
        print("    Événements abonnés (première page) : " + (", ".join(events) or "aucun constaté"))
    print("Important : GET ne renvoie jamais signing_secret. Pour un HTTP 401 HMAC,")
    print("comparer le secret du webhook LIVE dans le dashboard avec SASPAY_WEBHOOK_SECRET.")
    print("Si perdu : faire une rotation depuis le dashboard et mettre à jour le backend.")


def diagnose(env: dict[str, str], amount=None, organization_id=None,
             suggest_amounts=False, check_webhooks=False, payout_amount=None):
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
                print_quote_diagnostic(
                    rates, Decimal(amount), Decimal(raw_cap),
                    suggest_amounts=suggest_amounts,
                    allow_fractional=env.get("SASPAY_ALLOW_FRACTIONAL_XOF_FEES", "false").lower() == "true")
        if payout_amount is not None:
            print_payout_diagnostic(rates, env, Decimal(payout_amount))
        if not senegal:
            print("Aucun tarif pay-in compatible constaté; ne pas activer les paiements.")
        if check_webhooks:
            diagnose_webhooks(env, key)
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
    parser.add_argument("--check-webhooks", action="store_true",
                        help="Vérifie URL et abonnements des webhooks LIVE via GET seulement")
    parser.add_argument("--payout-amount-xof", type=int,
                        help="Pré-diagnostic lecture seule des réseaux et frais payout à ce montant")
    args = parser.parse_args()
    if args.amount_xof is not None and args.amount_xof <= 0:
        parser.error("--amount-xof doit être positif")
    variables = dict(load_env_file(args.env_file)) if args.env_file else {}
    variables.update(os.environ)
    if args.suggest_amounts and args.amount_xof is None:
        parser.error("--suggest-amounts nécessite --amount-xof")
    if args.payout_amount_xof is not None and args.payout_amount_xof <= 0:
        parser.error("--payout-amount-xof doit être positif")
    raise SystemExit(diagnose(variables, args.amount_xof, args.organization_id,
                              args.suggest_amounts, args.check_webhooks,
                              args.payout_amount_xof))
