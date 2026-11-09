"""Read-only SasPay diagnostic. No payment/transfer POST and no credential logging.

Example:
  python tools/saspay_diagnostics.py --env-file Backend/.env
Only the configured API hostname is contacted, using GET requests.
"""
from __future__ import annotations
import argparse
import json
import os
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


def diagnose(env: dict[str, str]):
    key = env.get("SASPAY_API_KEY", "")
    if not (key.startswith("sk_live_") or key.startswith("sk_test_")):
        print("SASPAY_API_KEY manquante ou de format inconnu.")
        return 1
    print("Diagnostic SasPay en lecture seule : aucun paiement, aucun transfert.")
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
    args = parser.parse_args()
    variables = dict(load_env_file(args.env_file)) if args.env_file else {}
    variables.update(os.environ)
    raise SystemExit(diagnose(variables))
