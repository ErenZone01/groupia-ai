"""Strict frontend npm audit gate with a narrowly documented, dev-only advisory exception.

No advisory is silently ignored. The known picomatch high-severity ReDoS
GHSA-fc5q-39xc-vjpf had no upstream fixed version as of 2026-11-18.
All production high/critical findings and all unrelated dev high/critical
findings fail CI. The temporary exception expires automatically once npm
reports that a picomatch fix is available.
"""
from __future__ import annotations

import json
import subprocess
import sys

ALLOWED_ADVISORY = "GHSA-fc5q-39xc-vjpf"
ALLOWED_PACKAGE = "picomatch"
RISK_LEVELS = {"high", "critical"}


def vulnerable(report):
    if not isinstance(report, dict) or "error" in report:
        raise ValueError("npm audit response incomplete or failed")
    entries = report.get("vulnerabilities")
    if not isinstance(entries, dict):
        raise ValueError("npm audit vulnerabilities missing")
    for name, entry in entries.items():
        if not isinstance(name, str) or not isinstance(entry, dict):
            raise ValueError("npm audit vulnerabilities malformed")
        if entry.get("severity") not in {"low", "moderate", "high", "critical", "info"}:
            raise ValueError("npm audit severity unrecognized")
        if not isinstance(entry.get("via"), list):
            raise ValueError("npm audit causal advisories missing")
    return entries


def vulnerability_roots(entries, name, stack=None):
    """Find all root security advisories, failing closed on missing or cyclic links."""
    stack = set() if stack is None else stack
    if name in stack:
        raise ValueError("npm audit dependency cycle: " + name)
    entry = entries.get(name)
    if entry is None:
        raise ValueError("npm audit dependency missing: " + name)
    roots = []
    for cause in entry["via"]:
        if isinstance(cause, str):
            roots.extend(vulnerability_roots(entries, cause, stack | {name}))
        elif isinstance(cause, dict):
            roots.append(cause)
        else:
            raise ValueError("npm audit cause malformed: " + name)
    if not roots:
        raise ValueError("npm audit cause absent: " + name)
    return roots


def inspect(full_report, production_report):
    """Return exception packages and findings; raises on any unapproved high risk."""
    prod = vulnerable(production_report)
    prod_bad = [name for name, finding in prod.items()
                if finding["severity"] in RISK_LEVELS]
    if prod_bad:
        raise ValueError("Production high/critical audit findings: " + ", ".join(sorted(prod_bad)))

    full = vulnerable(full_report)
    exceptions = []
    for name, finding in full.items():
        if finding["severity"] not in RISK_LEVELS:
            continue
        roots = vulnerability_roots(full, name)
        # An affected package can inherit both MODERATE minimatch findings and
        # HIGH picomatch findings. Only HIGH/CRITICAL roots trigger this gate;
        # moderate findings remain reported under the existing audit threshold.
        high_roots = [r for r in roots if r.get("severity") in RISK_LEVELS]
        if not high_roots:
            raise ValueError("High risk without a high/critical root advisory: " + name)
        for root in high_roots:
            url = root.get("url")
            advisory_match = (
                isinstance(url, str)
                and url.rstrip("/").endswith("/" + ALLOWED_ADVISORY)
                and root.get("name") == ALLOWED_PACKAGE
                and root.get("severity") == "high"
            )
            if not advisory_match:
                raise ValueError("Unapproved high/critical advisory for " + name)
        # If a fix is published, CI must demand upgrading rather than hiding it.
        if name == ALLOWED_PACKAGE and finding.get("fixAvailable") is not False:
            raise ValueError("picomatch fix now available or remediation status unknown")
        exceptions.append(name)
    if exceptions and ALLOWED_PACKAGE not in exceptions:
        raise ValueError("High-risk exception lacks its expected root package")
    return sorted(exceptions), len(prod), len(full)


def get_report(arguments):
    proc = subprocess.run(
        ["npm", "audit", "--json", *arguments],
        capture_output=True, text=True, check=False, timeout=120,
    )
    if proc.returncode not in (0, 1):
        raise ValueError("npm audit failed before a valid report")
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError as ex:
        raise ValueError("npm audit JSON invalid") from ex


def main():
    try:
        # The CI must not ignore a server-shipped dependency vulnerability.
        production = get_report(["--omit=dev", "--audit-level=high"])
        full = get_report(["--audit-level=high"])
        excepted, prod_count, total_count = inspect(full, production)
    except (ValueError, OSError, subprocess.TimeoutExpired) as ex:
        print("SECURITY GATE FAILURE: " + str(ex), file=sys.stderr)
        return 1
    print("npm audit production: " + str(prod_count)
          + " reported findings; zero high/critical accepted.")
    print("npm audit full tree: " + str(total_count) + " reported findings.")
    if excepted:
        print("TEMPORARY DEV-ONLY SECURITY EXCEPTION: " + ALLOWED_ADVISORY)
        print("Affected dependency entries: " + ", ".join(excepted))
        print("No vendor fix currently available. CI MUST fail if a patch emerges,")
        print("if a production high/critical appears, or if any other high/critical appears.")
        print("See Backend/docs/saspay/PAYOUT_SUCCESS_EVIDENCE_DIAGNOSTIC.md and tools/check_frontend_audit.py")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
