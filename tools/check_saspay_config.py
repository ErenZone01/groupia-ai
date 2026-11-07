"""Offline SasPay V1 configuration check. Never sends HTTP or prints credential values."""
import os
import re
import sys
from uuid import UUID


def check(env):
    enabled = env.get("SASPAY_ENABLED", "false").lower()
    if enabled not in ("true", "false"):
        return ["SASPAY_ENABLED must be true or false"]
    if enabled == "false":
        return []
    errors = []
    if not re.fullmatch(r"sk_test_[A-Za-z0-9_-]+", env.get("SASPAY_API_KEY", "")):
        errors.append("SASPAY_API_KEY must be a test key; live collection is disabled in V1")
    if not env.get("SASPAY_WEBHOOK_SECRET", "").strip():
        errors.append("SASPAY_WEBHOOK_SECRET is required")
    try:
        UUID(env.get("SASPAY_MERCHANT_ID", ""))
    except ValueError:
        errors.append("SASPAY_MERCHANT_ID must be a UUID")
    hosts = env.get("SASPAY_CHECKOUT_HOSTS", "")
    if hosts and any(not re.fullmatch(r"[a-z0-9]+(?:[.-][a-z0-9]+)*\.[a-z]{2,}", host.strip()) for host in hosts.split(",")):
        errors.append("SASPAY_CHECKOUT_HOSTS must contain exact HTTPS hostnames, without URLs or wildcards")
    return errors


if __name__ == "__main__":
    errors = check(os.environ)
    for error in errors:
        print(error)
    print("SasPay offline configuration: " + ("FAIL" if errors else "PASS") + "; no provider request sent")
    sys.exit(bool(errors))
