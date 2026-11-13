package sn.groupia.settlement.infrastructure.provider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import sn.groupia.settlement.domain.model.MerchantPayout;
import tools.jackson.databind.JsonNode;

/**
 * Strict, read-only SasPay preflight before the irreversible POST. A wallet balance is only
 * liquidity evidence, never proof of Groupia ownership of the funds. Company-owned fees must also
 * be reserved durably in the separate fee ledger.
 */
final class SaspayPayoutPreflight {
    private static final BigDecimal MAX = new BigDecimal("100000000000000");

    private SaspayPayoutPreflight() {}

    static BigDecimal checkRates(
            JsonNode rows, MerchantPayout payout, String network, BigDecimal approvedCeiling) {
        if (rows == null
                || !rows.isArray()
                || !network.matches("[a-z0-9_]{3,64}")
                || approvedCeiling == null
                || approvedCeiling.signum() <= 0
                || approvedCeiling.scale() > 2)
            throw new IllegalStateException("SASPAY_PAYOUT_PREFLIGHT_UNAVAILABLE");
        var matchedNetworks = new ArrayList<JsonNode>();
        for (JsonNode row : rows) {
            if ("SN".equals(string(row, "country_code"))
                    && "XOF".equals(string(row, "currency"))
                    && network.equals(string(row, "network_code"))) matchedNetworks.add(row);
        }
        if (matchedNetworks.size() != 1)
            throw new IllegalStateException("SASPAY_PAYOUT_NETWORK_UNAVAILABLE");
        JsonNode payoutRate = matchedNetworks.getFirst().path("payout");
        if (!payoutRate.path("available").isBoolean()
                || !payoutRate.path("available").booleanValue()
                || !payoutRate.path("otp_required").isBoolean()
                || payoutRate.path("otp_required").booleanValue()
                || !payoutRate.path("tiers").isArray())
            throw new IllegalStateException("SASPAY_PAYOUT_NETWORK_UNAVAILABLE");

        var tiers = new ArrayList<JsonNode>();
        for (var tier : payoutRate.path("tiers")) {
            BigDecimal lower = decimal(tier, "min_amount");
            BigDecimal upper = optional(tier, "max_amount");
            if (payout.amount().compareTo(lower) >= 0
                    && (upper == null || payout.amount().compareTo(upper) <= 0)) tiers.add(tier);
        }
        if (tiers.size() != 1) throw new IllegalStateException("SASPAY_PAYOUT_RATE_AMBIGUOUS");
        var tier = tiers.getFirst();
        if (!"XOF".equals(string(tier, "currency"))
                || !"DEDUCTED".equals(string(tier, "fee_charge_mode")))
            throw new IllegalStateException("SASPAY_PAYOUT_DEDUCTED_UNAVAILABLE");

        BigDecimal percent = decimal(tier, "percent");
        BigDecimal fixed = decimal(tier, "fixed");
        BigDecimal floor = optional(tier, "floor_amount");
        BigDecimal cap = optional(tier, "cap_amount");
        if (floor != null && cap != null && floor.compareTo(cap) > 0)
            throw new IllegalStateException("SASPAY_PAYOUT_RATE_INVALID");
        BigDecimal fee = grossAmount.multiply(percent).movePointLeft(2).add(fixed);
        if (floor != null) fee = fee.max(floor);
        if (cap != null) fee = fee.min(cap);
        try {
            // No guessed rounding, including fractional XOF. Providers can return 2 dp.
            fee = fee.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("SASPAY_PAYOUT_FEE_PRECISION_UNCONFIRMED");
        }
        if (fee.compareTo(grossAmount) >= 0)
            throw new IllegalStateException("SASPAY_PAYOUT_NET_AMOUNT_NOT_POSITIVE");
        return fee;
    }

    static void checkWallet(
            JsonNode response, MerchantPayout payout, BigDecimal fee, String merchantId) {
        if (response == null
                || !response.isObject()
                || !response.path("results").isArray()
                || response.hasNonNull("next"))
            throw new IllegalStateException("SASPAY_PAYOUT_WALLET_UNVERIFIED");
        var entries = response.path("results");
        if (response.has("count")) {
            JsonNode count = response.path("count");
            if (!count.canConvertToInt() || count.intValue() != entries.size())
                throw new IllegalStateException("SASPAY_PAYOUT_WALLET_UNVERIFIED");
        }
        var found = new ArrayList<JsonNode>();
        for (var row : entries) {
            if ("SN".equals(string(row, "country"))
                    && "XOF".equals(string(row, "currency"))
                    && merchantId.equals(string(row, "merchant"))) found.add(row);
        }
        if (found.size() != 1) throw new IllegalStateException("SASPAY_PAYOUT_WALLET_UNVERIFIED");
        var wallet = found.getFirst();
        if (!wallet.path("is_frozen").isBoolean() || wallet.path("is_frozen").booleanValue())
            throw new IllegalStateException("SASPAY_PAYOUT_WALLET_FROZEN");
        BigDecimal available = decimal(wallet, "available_amount");
        if (available.compareTo(payout.amount()) < 0)
            throw new IllegalStateException("SASPAY_PAYOUT_WALLET_INSUFFICIENT");
    }

    private static String string(JsonNode row, String name) {
        JsonNode value = row.path(name);
        return value.isTextual() ? value.asText() : null;
    }

    private static BigDecimal optional(JsonNode row, String field) {
        if (!row.has(field)) throw new IllegalStateException("SASPAY_PAYOUT_RATE_INCOMPLETE");
        return row.path(field).isNull() ? null : decimal(row, field);
    }

    private static BigDecimal decimal(JsonNode row, String field) {
        JsonNode v = row.path(field);
        BigDecimal value;
        try {
            if (v.isTextual()) {
                if (!v.asText().matches("[0-9]{1,14}(?:\\.[0-9]{1,6})?"))
                    throw new IllegalStateException("SASPAY_PAYOUT_RATE_INVALID");
                value = new BigDecimal(v.asText());
            } else if (v.isNumber()) {
                value = v.decimalValue();
            } else {
                throw new IllegalStateException("SASPAY_PAYOUT_RATE_INVALID");
            }
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("SASPAY_PAYOUT_RATE_INVALID");
        }
        if (value.signum() < 0
                || value.compareTo(MAX) >= 0
                || value.stripTrailingZeros().scale() > 6)
            throw new IllegalStateException("SASPAY_PAYOUT_RATE_INVALID");
        return value;
    }
}
