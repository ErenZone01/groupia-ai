"""Bounded GET-only payout investigation. Empty history is NOT proof of non-execution."""
import argparse
from datetime import datetime, timezone
from decimal import Decimal
import json
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import UUID
from saspay_diagnostics import load_env_file

ROOT = "https://api.saspay.me/api/v1/transactions/"


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def validate_url(url):
    parsed = urlsplit(url)
    if (parsed.scheme != "https" or parsed.netloc != "api.saspay.me"
            or parsed.path != "/api/v1/transactions/" or parsed.fragment):
        raise ValueError("Pagination hors endpoint autorise")
    return url


def fetch(url, key):
    request = Request(validate_url(url), method="GET", headers={
        "Authorization": "Bearer " + key, "Accept": "application/json"})
    with build_opener(NoRedirect()).open(request, timeout=15) as reply:
        if reply.status != 200:
            raise ValueError("Statut HTTP inattendu")
        raw = reply.read(1048577)
        if len(raw) > 1048576:
            raise ValueError("Reponse trop volumineuse")
        return json.loads(raw, parse_float=Decimal)


def audit(key, merchant, since, until, get=fetch):
    merchant = str(UUID(merchant))
    for value in (since, until):
        if datetime.fromisoformat(value.replace("Z", "+00:00")).tzinfo is None:
            raise ValueError("Horodatage UTC requis")
    url = ROOT + "?" + urlencode({"merchant": merchant, "transaction_type": "RETRAIT",
        "flow_direction": "OUTBOUND", "currency": "XOF", "created_at__gte": since,
        "created_at__lte": until, "page_size": 100})
    seen, candidates, identifiers = set(), [], set()
    for _ in range(50):
        validate_url(url)
        if url in seen:
            raise ValueError("Boucle de pagination")
        seen.add(url)
        root = get(url, key)
        if not isinstance(root, dict):
            raise ValueError("Enveloppe invalide")
        if "success" in root:
            if root["success"] is not True:
                raise ValueError("Rejet fournisseur")
            root = root.get("data")
        if not isinstance(root, dict) or not isinstance(root.get("results"), list):
            raise ValueError("Pagination invalide")
        for row in root["results"]:
            if not isinstance(row, dict):
                raise ValueError("Transaction invalide")
            identifier = str(UUID(row["id"]))
            if identifier in identifiers:
                raise ValueError("Transaction dupliquee entre pages")
            identifiers.add(identifier)
            status = row.get("status")
            candidates.append({"id": identifier,
                "status": status if status in ("PENDING", "SUCCESS", "FAILED", "CANCELLED") else "UNKNOWN"})
        url = root.get("next")
        if not url:
            return {"pages": len(seen), "candidates": candidates,
                    "definitive_non_execution_proven": False}
    raise ValueError("Limite de pagination atteinte")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", default="Backend/.env")
    parser.add_argument("--merchant-id", required=True)
    parser.add_argument("--since", required=True)
    args = parser.parse_args()
    values = load_env_file(args.env_file)
    key = values.get("SASPAY_API_KEY", "")
    if not key:
        raise SystemExit("Cle absente (valeur jamais affichee)")
    try:
        result = audit(key, args.merchant_id, args.since, datetime.now(timezone.utc).isoformat())
        print(json.dumps(result))
        print("GET uniquement. Aucun resultat de liste ne constitue une attestation de non-execution.")
    except HTTPError as exc:
        raise SystemExit("GET SasPay refuse, HTTP " + str(exc.code)) from None
    except (URLError, ValueError, KeyError, TypeError):
        raise SystemExit("Investigation GET incomplete ; aucune conclusion financiere.") from None


if __name__ == "__main__":
    main()
