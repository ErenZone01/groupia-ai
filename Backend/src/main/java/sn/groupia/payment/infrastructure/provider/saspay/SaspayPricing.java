package sn.groupia.payment.infrastructure.provider.saspay;

import java.math.*;
import java.util.*;
import sn.groupia.payment.domain.service.ProviderFee;
import tools.jackson.databind.JsonNode;

final class SaspayPricing {
    private static final BigDecimal MONEY_LIMIT = new BigDecimal("100000000000000");

    static ProviderFee quote(JsonNode rates, BigDecimal amount, String currency, String network) {
        if (!"XOF".equals(currency)
                || amount == null
                || amount.signum() <= 0
                || amount.compareTo(MONEY_LIMIT) >= 0
                || amount.stripTrailingZeros().scale() > 0
                || network == null
                || !network.matches("[a-z0-9_]{1,64}")
                || !rates.isArray()) throw new SaspayException("SASPAY_UNSUPPORTED_QUOTE");
        var matches = new ArrayList<JsonNode>();
        for (var rate : rates) {
            if (!"SN".equals(rate.path("country_code").asText())
                    || !currency.equals(rate.path("currency").asText())
                    || !network.equals(rate.path("network_code").asText())) continue;
            var payin = rate.path("payin");
            if (!payin.path("available").isBoolean()
                    || !payin.path("available").booleanValue()
                    || !payin.path("otp_required").isBoolean()
                    || payin.path("otp_required").booleanValue()
                    || !payin.path("tiers").isArray())
                throw new SaspayException("SASPAY_NETWORK_UNAVAILABLE");
            for (var tier : payin.path("tiers")) {
                // Do not guess inclusivity at a shared tier boundary: multiple matches fail closed.
                var min = decimal(tier, "min_amount");
                var max = optional(tier, "max_amount");
                if (amount.compareTo(min) >= 0 && (max == null || amount.compareTo(max) <= 0))
                    matches.add(tier);
            }
        }
        if (matches.size() != 1) throw new SaspayException("SASPAY_AMBIGUOUS_RATE");
        var tier = matches.getFirst();
        if (!currency.equals(tier.path("currency").asText())
                || !"ADD_ON".equals(tier.path("fee_charge_mode").asText()))
            throw new SaspayException("SASPAY_ADD_ON_REQUIRED");
        var percent = decimal(tier, "percent");
        var fixed = decimal(tier, "fixed");
        var floor = optional(tier, "floor_amount");
        var cap = optional(tier, "cap_amount");
        if (floor != null && cap != null && floor.compareTo(cap) > 0)
            throw new SaspayException("SASPAY_INVALID_RATE");
        var fee = amount.multiply(percent).movePointLeft(2).add(fixed);
        if (floor != null) fee = fee.max(floor);
        if (cap != null) fee = fee.min(cap);
        // Official examples contain fractional XOF, but no rounding contract. Never silently round.
        try {
            fee = fee.setScale(0, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new SaspayException("SASPAY_XOF_ROUNDING_UNCONFIRMED");
        }
        // Keep every quoted value within the verifier's exact decimal range and JS safe integers.
        if (amount.add(fee).compareTo(MONEY_LIMIT) >= 0)
            throw new SaspayException("SASPAY_UNSUPPORTED_QUOTE");
        return new ProviderFee("SN", network, network, fee);
    }

    static BigDecimal decimal(JsonNode node, String field) {
        var value = node.path(field);
        // SasPay can return JSON numbers even though the public examples use strings.
        // Convert the JSON decimal representation directly, never via double arithmetic.
        if ((!value.isTextual() && !value.isNumber())
                || !value.asText().matches("[0-9]{1,14}(?:\\.[0-9]{1,6})?"))
            throw new SaspayException("SASPAY_INVALID_MONEY");
        return new BigDecimal(value.asText());
    }

    private static BigDecimal optional(JsonNode node, String field) {
        if (!node.has(field)) throw new SaspayException("SASPAY_INCOMPLETE_RATE");
        return node.get(field).isNull() ? null : decimal(node, field);
    }
}
