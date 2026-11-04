#!/usr/bin/env python3
"""Read-only sandbox check for a direct order. Never starts or retries a payment."""

import argparse
import getpass
import json
import re
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


TOKEN_PATTERN = re.compile(r"^[A-Za-z0-9_-]{43}$")
PAID_ORDER_STATES = {
    "PAYMENT_CONFIRMED", "PREPARING", "READY_FOR_PICKUP", "READY_FOR_DISPATCH"
}
FINANCIAL_STATES = {
    "NOT_PREPARED", "PREPARED", "PAYMENT_IN_PROGRESS", "SETTLEMENT_PROCESSING",
    "SETTLEMENT_VERIFIED", "REVIEW_REQUIRED", "UNAVAILABLE", "EXPIRED",
}


class UnsafePreflight(ValueError):
    """No money may move when a preflight fails."""


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        # A redirect must never forward a private capability, even from localhost.
        return None


def local_base_url(value):
    parsed = urlsplit(value)
    if (
        parsed.scheme != "http"
        or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}
        or parsed.username is not None
        or parsed.password is not None
        or parsed.path not in {"", "/"}
        or parsed.query
        or parsed.fragment
        or parsed.port is None
    ):
        raise UnsafePreflight("Le contrôle doit cibler un serveur HTTP local explicite.")
    return value.rstrip("/")


def request_private_status(base, token, path, opener=None):
    if path not in {
        "/api/public/direct-orders/lookup",
        "/api/public/direct-orders/payment-status",
    }:
        raise UnsafePreflight("Route de vérification non autorisée.")
    if not TOKEN_PATTERN.fullmatch(token):
        raise UnsafePreflight("Jeton privé invalide.")
    endpoint = local_base_url(base) + path
    payload = json.dumps({"token": token}).encode("utf-8")
    request = Request(
        endpoint,
        data=payload,
        method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    connection = opener if opener is not None else build_opener(NoRedirects())
    with connection.open(request, timeout=12) as response:
        if response.status != 200:
            raise UnsafePreflight("Réponse HTTP inattendue.")
        raw = response.read(65537)
        if len(raw) > 65536:
            raise UnsafePreflight("Réponse trop volumineuse.")
        result = json.loads(raw)
    if not isinstance(result, dict):
        raise UnsafePreflight("Réponse serveur invalide.")
    return result


def verify_pair(tracking, payment):
    """Require coherent server reads; never infer settlement from checkout or redirect."""
    if (
        not isinstance(tracking.get("orderNumber"), str)
        or not tracking["orderNumber"].startswith("DIR-")
        or tracking.get("orderNumber") != payment.get("orderNumber")
        or tracking.get("currency") != "XOF"
        or payment.get("currency") != "XOF"
    ):
        raise UnsafePreflight("Identité de la commande ou devise incohérente.")

    total = tracking.get("acceptedTotalXof")
    other_total = payment.get("acceptedTotalXof")
    if (
        not isinstance(total, int)
        or isinstance(total, bool)
        or total <= 0
        or not isinstance(other_total, int)
        or isinstance(other_total, bool)
        or total != other_total
    ):
        raise UnsafePreflight("Total accepté incohérent.")

    state = payment.get("financialStatus")
    available = payment.get("paymentAvailable")
    if state not in FINANCIAL_STATES or not isinstance(available, bool):
        raise UnsafePreflight("État financier inconnu.")
    if available and state != "PREPARED":
        raise UnsafePreflight("Disponibilité du paiement incohérente.")
    if state == "SETTLEMENT_VERIFIED" and tracking.get("status") not in PAID_ORDER_STATES:
        raise UnsafePreflight("Règlement non cohérent avec l'état de la commande.")
    if state == "PREPARED" and tracking.get("status") != "PENDING_PAYMENT_INTEGRATION":
        raise UnsafePreflight("Tentative préparée pour un état de commande incompatible.")

    # Only read-only, non-sensitive fields; never print the capability or raw payload.
    return (
        f"Commande : {tracking['orderNumber']} | Total : {total} XOF | "
        f"État financier : {state} | Paiement proposé : {'oui' if available else 'non'}"
    )


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Lecture sécurisée d'une commande directe en sandbox locale ; aucun débit."
    )
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    args = parser.parse_args(argv)
    try:
        local_base_url(args.base_url)
        token = getpass.getpass("Jeton privé de récupération (saisie masquée) : ")
        if not TOKEN_PATTERN.fullmatch(token):
            raise UnsafePreflight("Jeton privé invalide.")
        tracking = request_private_status(
            args.base_url, token, "/api/public/direct-orders/lookup"
        )
        payment = request_private_status(
            args.base_url, token, "/api/public/direct-orders/payment-status"
        )
        print(verify_pair(tracking, payment))
        return 0
    except (UnsafePreflight, HTTPError, URLError, ValueError, json.JSONDecodeError):
        print("Vérification impossible ou incohérente : n'initiez pas un autre paiement.")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
