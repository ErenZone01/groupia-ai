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
    fractional = env.get("SASPAY_ALLOW_FRACTIONAL_XOF_FEES", "false").lower()
    if fractional not in ("true", "false"):
        errors.append("SASPAY_ALLOW_FRACTIONAL_XOF_FEES must be true or false")
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
    production = env.get("SASPAY_PRODUCTION_ENABLED", "false").lower()
    if production not in ("true", "false"):
        errors.append("SASPAY_PRODUCTION_ENABLED must be true or false")
    profiles = {profile.strip() for profile in env.get("SPRING_PROFILES_ACTIVE", "").split(",")}
    expected_url = "https://api.saspay.me/api/v1" if live else "https://sandbox-api.saspay.me/api/v1"
    if env.get("SASPAY_API_URL", "") not in ("", expected_url):
        errors.append("SasPay API environment must match the key environment")
    if live:
        if "prod" not in profiles:
            errors.append("Live key requires SPRING_PROFILES_ACTIVE=prod")
        if production == "true":
            if pilot != "false":
                errors.append("Production and live pilot modes are mutually exclusive")
            charged = env.get("SASPAY_PRODUCTION_MAX_CHARGED_XOF", "1000000")
            if not charged.isascii() or not charged.isdigit() or not 1 <= int(charged) <= 10000000:
                errors.append("Production limit must be an integer from 1 to 10000000 XOF, fees included")
        else:
            if pilot != "true":
                errors.append("Live key requires explicit production or live pilot mode")
            try:
                UUID(env.get("SASPAY_LIVE_PILOT_ORGANIZATION_ID", ""))
            except (ValueError, TypeError):
                errors.append("Live pilot requires one valid organization UUID")
            charged = env.get("SASPAY_LIVE_PILOT_MAX_CHARGED_XOF", "2000")
            if not charged.isascii() or not charged.isdigit() or not 1 <= int(charged) <= 5000:
                errors.append("Live pilot limit must be an integer from 1 to 5000 XOF, fees included")
        if not hosts:
            errors.append("Live mode requires exact checkout hostname(s)")
    elif pilot == "true" or production == "true" or "prod" in profiles:
        errors.append("Live modes and prod profile require a live key")
    return errors


if __name__ == "__main__":
    errors = check(os.environ)
    for error in errors:
        print(error)
    print("SasPay offline configuration: " + ("FAIL" if errors else "PASS") + "; no provider request sent")
    sys.exit(bool(errors))
