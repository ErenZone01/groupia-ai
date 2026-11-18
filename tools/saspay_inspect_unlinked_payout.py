#!/usr/bin/env python3
"""SasPay LIVE transaction search for historical payouts WITHOUT provider ID.

GET /transactions/ only. Does not change Groupia or provider state, never POSTs,
never proves non-execution from absence of matches. Output deliberately excludes PII.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path

BASE = "https://api.saspay.me"
PATH = "/api/v1/transactions/"
MAX_BODY = 262_144
MATCH_TYPES = {"RETRAIT", "TRANSFERT"}
CURSOR = re.compile(r"[A-Za-z0-9_+\-/=]{1,1024}\Z")


class ReadOnlyDiagnosticError(Exception):
    """Static, non-sensitive diagnostics: never include provider response bodies."""


def env_values(path: Path) -> dict[str, str]:
    # Parse assignments only. No sourcing, variable expansion or script execution.
    values = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].strip()
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[key] = value
    return values


def as_utc(raw: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(raw.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError("timezone required")
        return parsed.astimezone(timezone.utc)
    except ValueError as ex:
        raise ReadOnlyDiagnosticError("DATE_INVALID_OR_WITHOUT_TIMEZONE") from ex


def as_uuid(raw: str) -> str:
    try:
        return str(uuid.UUID(raw))
    except (ValueError, AttributeError, TypeError) as ex:
        raise ReadOnlyDiagnosticError("IDENTIFIER_INVALID") from ex


def next_cursor(next_url: object) -> str | None:
    if next_url is None:
        return None
    if not isinstance(next_url, str) or not next_url:
        raise ReadOnlyDiagnosticError("PAGINATION_URL_INVALID")
    parsed = urllib.parse.urlsplit(next_url)
    # Treat the next URL strictly as an untrusted *cursor source*, not a URL to fetch.
    if (parsed.scheme != "https" or parsed.hostname != "api.saspay.me"
            or parsed.port is not None or parsed.username is not None
            or parsed.password is not None or parsed.path != PATH or parsed.fragment):
        raise ReadOnlyDiagnosticError("PAGINATION_URL_UNTRUSTED")
    query = urllib.parse.parse_qs(parsed.query, strict_parsing=True, keep_blank_values=True)
    if any(k not in {"cursor", "page_size", "merchant", "currency", "flow_direction",
                          "created_at__gte", "created_at__lte", "transaction_type", "status"}
           for k in query) or len(query.get("cursor", [])) != 1:
        raise ReadOnlyDiagnosticError("PAGINATION_CURSOR_MISSING_OR_UNTRUSTED")
    value = query["cursor"][0]
    if not CURSOR.fullmatch(value):
        raise ReadOnlyDiagnosticError("PAGINATION_CURSOR_INVALID")
    return value


def request_url(params: dict[str, str], cursor: str | None) -> str:
    query = dict(params)
    if cursor is not None:
        query["cursor"] = cursor
    return BASE + PATH + "?" + urllib.parse.urlencode(query)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ReadOnlyDiagnosticError("REDIRECT_REFUSED")


def fetch_page(url: str, api_key: str) -> dict:
    req = urllib.request.Request(
        url, headers={"Authorization": "Bearer " + api_key, "Accept": "application/json"},
        method="GET",
    )
    try:
        with urllib.request.build_opener(NoRedirect).open(req, timeout=10) as response:
            if response.status != 200:
                raise ReadOnlyDiagnosticError("HTTP_UNEXPECTED_STATUS")
            if response.headers.get_content_type() != "application/json":
                raise ReadOnlyDiagnosticError("CONTENT_TYPE_INVALID")
            body = response.read(MAX_BODY + 1)
    except ReadOnlyDiagnosticError:
        raise
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError):
        # Deliberately do not include provider URL, message, body or headers.
        raise ReadOnlyDiagnosticError("GET_FAILED_OR_UNCERTAIN") from None
    if len(body) > MAX_BODY:
        raise ReadOnlyDiagnosticError("RESPONSE_TOO_LARGE")
    try:
        data = json.loads(body)
    except (ValueError, UnicodeError) as ex:
        raise ReadOnlyDiagnosticError("JSON_INVALID") from ex
    if isinstance(data, dict) and "success" in data:
        if data.get("success") is not True:
            raise ReadOnlyDiagnosticError("PROVIDER_REPORTED_FAILURE")
        data = data.get("data")
    if not isinstance(data, dict) or not isinstance(data.get("results"), list):
        raise ReadOnlyDiagnosticError("PAGINATION_ENVELOPE_INVALID")
    return data


def scan_pages(api_key: str, merchant: str, start: datetime, end: datetime,
               amount: Decimal, max_pages: int, get_page=fetch_page) -> tuple[int, int, list[dict]]:
    params = {
        "merchant": merchant, "flow_direction": "OUTBOUND", "currency": "XOF",
        "created_at__gte": start.isoformat().replace("+00:00", "Z"),
        "created_at__lte": end.isoformat().replace("+00:00", "Z"), "page_size": "100",
    }
    cursor = None
    cursors = set()
    transactions = set()
    found = []
    count = 0
    for index in range(max_pages):
        doc = get_page(request_url(params, cursor), api_key)
        if not isinstance(doc, dict) or not isinstance(doc.get("results"), list):
            raise ReadOnlyDiagnosticError("PAGINATION_ENVELOPE_INVALID")
        count += len(doc["results"])
        if count > 100 * max_pages:
            raise ReadOnlyDiagnosticError("PAGE_LIMIT_EXCEEDED")
        for row in doc["results"]:
            if not isinstance(row, dict):
                raise ReadOnlyDiagnosticError("ROW_INVALID")
            identifier = as_uuid(row.get("id"))
            if identifier in transactions:
                raise ReadOnlyDiagnosticError("DUPLICATE_TRANSACTION_ACROSS_PAGES")
            transactions.add(identifier)
            try:
                value = Decimal(str(row["requested_amount"]))
                occurred = as_utc(row["created_at"])
            except (KeyError, InvalidOperation, TypeError) as ex:
                raise ReadOnlyDiagnosticError("ROW_FINANCIAL_EVIDENCE_INVALID") from ex
            if (as_uuid(row.get("merchant")) == merchant and row.get("currency") == "XOF"
                    and row.get("flow_direction") == "OUTBOUND"
                    and row.get("transaction_type") in MATCH_TYPES
                    and value == amount and start <= occurred <= end):
                found.append({"provider_id": identifier,
                              "status": row.get("status") if row.get("status") in
                                        {"PENDING", "SUCCESS", "FAILED", "CANCELLED"}
                                        else "UNKNOWN",
                              "transaction_type": row["transaction_type"],
                              "created_at": occurred.isoformat()})
        cursor = next_cursor(doc.get("next"))
        if cursor is None:
            return index + 1, count, found
        if cursor in cursors:
            raise ReadOnlyDiagnosticError("PAGINATION_CURSOR_LOOP")
        cursors.add(cursor)
    raise ReadOnlyDiagnosticError("PAGINATION_INCOMPLETE_LIMIT_REACHED")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="GET-only SasPay unlinked payout investigation")
    parser.add_argument("--env-file", type=Path, default=Path("Backend/.env"))
    parser.add_argument("--from-utc", required=True, help="ISO-8601 datetime with timezone")
    parser.add_argument("--to-utc", required=True, help="ISO-8601 datetime with timezone")
    parser.add_argument("--expected-amount-xof", default="1000")
    parser.add_argument("--max-pages", type=int, default=25)
    options = parser.parse_args(argv)
    try:
        start, end = as_utc(options.from_utc), as_utc(options.to_utc)
        amount = Decimal(options.expected_amount_xof)
        if start >= end or end - start > __import__("datetime").timedelta(days=14):
            raise ReadOnlyDiagnosticError("DATE_WINDOW_INVALID")
        if not amount.is_finite() or amount <= 0 or amount != amount.to_integral_value():
            raise ReadOnlyDiagnosticError("AMOUNT_INVALID")
        if not 1 <= options.max_pages <= 100:
            raise ReadOnlyDiagnosticError("PAGE_LIMIT_INVALID")
        values = env_values(options.env_file)
        key = os.environ.get("SASPAY_API_KEY") or values.get("SASPAY_API_KEY", "")
        merchant_raw = os.environ.get("SASPAY_MERCHANT_ID") or values.get("SASPAY_MERCHANT_ID", "")
        if not key.startswith("sk_live_") or not re.fullmatch(r"sk_live_[A-Za-z0-9_-]+", key):
            raise ReadOnlyDiagnosticError("LIVE_KEY_REQUIRED")
        merchant = as_uuid(merchant_raw)
        pages, inspected, candidates = scan_pages(key, merchant, start, end, amount, options.max_pages)
        print(json.dumps({"mode": "GET_ONLY", "pages": pages, "transactions_read": inspected,
                          "candidates_not_proofs": candidates, "exhausted": True,
                          "non_execution_proven": False}, ensure_ascii=False, indent=2))
        print("REMARQUE: aucun resultat ne prouve PAS une non-execution. Ne pas relancer le POST.")
        return 0
    except (ReadOnlyDiagnosticError, OSError) as ex:
        safe = str(ex) if isinstance(ex, ReadOnlyDiagnosticError) else "ENV_FILE_UNREADABLE"
        print("DIAGNOSTIC_INCOMPLET: " + safe, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
