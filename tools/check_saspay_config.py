"""Offline SasPay configuration check. Never contacts SasPay or prints secrets."""
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
    key = env.get("SASPAY_API_KEY", "")
    test = bool(re.fullmatch(r"sk_test_[A-Za-z0-9_-]+", key))
    live = bool(re.fullmatch(r"sk_live_[A-Za-z0-9_-]+", key))
    if not test and not live:
        errors.append("SASPAY_API_KEY must be a valid sk_test_ or sk_live_ key")
    if not env.get("SASPAY_WEBHOOK_SECRET", "").strip():
        errors.append("SASPAY_WEBHOOK_SECRET is required")
    try:
        UUID(env.get("SASPAY_MERCHANT_ID", ""))
    except (ValueError, TypeError):
        errors.append("SASPAY_MERCHANT_ID must be a UUID")
    hosts = env.get("SASPAY_CHECKOUT_HOSTS", "")
    if hosts and any(not re.fullmatch(r"[a-z0-9]+(?:[.-][a-z0-9]+)*\.[a-z]{2,}", host.strip()) for host in hosts.split(",")):
        errors.append("SASPAY_CHECKOUT_HOSTS must contain exact HTTPS hostnames without wildcards")
    pilot = env.get("SASPAY_LIVE_PILOT_ENABLED", "false").lower()
    if pilot not in ("true", "false"):
        errors.append("SASPAY_LIVE_PILOT_ENABLED must be true or false")
    if live:
        if pilot != "true":
            errors.append("Live key requires SASPAY_LIVE_PILOT_ENABLED=true")
        if "prod" not in env.get("SPRING_PROFILES_ACTIVE", "").split(","):
            errors.append("Live key requires SPRING_PROFILES_ACTIVE=prod")
        try:
            UUID(env.get("SASPAY_LIVE_PILOT_ORGANIZATION_ID", ""))
        except (ValueError, TypeError):
            errors.append("Live pilot requires one valid organization UUID")
        charged = env.get("SASPAY_LIVE_PILOT_MAX_CHARGED_XOF", "2000")
        if not charged.isdigit() or not 1 <= int(charged) <= 5000:
            errors.append("Live pilot limit must be an integer from 1 to 5000 XOF, fees included")
        if not hosts:
            errors.append("Live pilot requires exact checkout hostname(s)")
    elif pilot == "true":
        errors.append("Live pilot cannot be enabled with a test key")
    return errors


if __name__ == "__main__":
    errors = check(os.environ)
    for error in errors:
        print(error)
    print("SasPay offline configuration: " + ("FAIL" if errors else "PASS") + "; no provider request sent")
    sys.exit(bool(errors))
