package sn.groupia.payment.infrastructure.provider.saspay;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import sn.groupia.shared.api.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Operator-triggered, bounded SasPay connectivity check. Its output is a diagnostic snapshot, NOT
 * proof of Groupia ledger backing, a money-movement authorization or settlement evidence.
 */
@Component
final class SaspayReadOnlyPreflight {
    private final SaspayProperties properties;
    private final Clock clock;
    private final ObjectMapper json;
    private final SaspayClient injectedClient;
    private final AtomicReference<Instant> nextAllowedCheck = new AtomicReference<>(Instant.MIN);

    record Snapshot(
            Instant checkedAt,
            String status,
            int sampledTransactions,
            boolean historyHasMorePages,
            int sampledBalanceRows,
            boolean balancesComplete,
            int senegalXofWallets,
            BigDecimal senegalAvailableXof,
            BigDecimal senegalPendingXof,
            boolean walletFrozen,
            String noteCode) {}

    @Autowired
    SaspayReadOnlyPreflight(SaspayProperties properties, Clock clock, ObjectMapper json) {
        this(properties, clock, json, null);
    }

    SaspayReadOnlyPreflight(
            SaspayProperties properties,
            Clock clock,
            ObjectMapper json,
            SaspayClient injectedClient) {
        this.properties = properties;
        this.clock = clock;
        this.json = json;
        this.injectedClient = injectedClient;
    }

    Snapshot check() {
        if (!properties.enabled() || properties.merchantId() == null)
            throw new ApiException(
                    503, "SASPAY_PREFLIGHT_DISABLED", "Configuration SasPay non activée.");
        Instant now = clock.instant();
        while (true) {
            Instant until = nextAllowedCheck.get();
            if (now.isBefore(until))
                throw new ApiException(
                        429,
                        "SASPAY_PREFLIGHT_COOLDOWN",
                        "Patientez avant de relancer le diagnostic fournisseur.");
            if (nextAllowedCheck.compareAndSet(until, now.plusSeconds(60))) break;
        }
        SaspayClient client =
                injectedClient == null
                        ? new SaspayClient(SaspayClient.builder(properties).build(), json, clock)
                        : injectedClient;
        JsonNode walletPage = client.merchantBalancesFirstPage();
        if (!walletPage.isObject() || !walletPage.path("results").isArray())
            throw new SaspayException("SASPAY_PREFLIGHT_BALANCES_INVALID");
        JsonNode walletRows = walletPage.path("results");
        if (walletRows.size() > 100) throw new SaspayException("SASPAY_PREFLIGHT_BALANCES_INVALID");

        boolean balancesComplete =
                walletPage.path("next").isNull() || walletPage.path("next").isMissingNode();
        JsonNode count = walletPage.path("count");
        if (!count.isIntegralNumber() || count.intValue() < walletRows.size())
            throw new SaspayException("SASPAY_PREFLIGHT_BALANCES_INVALID");
        if (count.intValue() > walletRows.size()) balancesComplete = false;

        int senegalWallets = 0;
        boolean frozen = false;
        BigDecimal available = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        for (JsonNode wallet : walletRows) {
            if (!wallet.isObject()
                    || !properties.merchantId().toString().equals(text(wallet, "merchant")))
                throw new SaspayException("SASPAY_PREFLIGHT_MERCHANT_MISMATCH");
            if (!"XOF".equals(text(wallet, "currency")) || !"SN".equals(text(wallet, "country")))
                continue;
            if (!wallet.path("is_frozen").isBoolean())
                throw new SaspayException("SASPAY_PREFLIGHT_BALANCES_INVALID");
            senegalWallets++;
            frozen |= wallet.path("is_frozen").booleanValue();
            available = available.add(money(wallet, "available_amount"));
            pending = pending.add(money(wallet, "pending_amount"));
        }

        // Read a *single* bounded history page, excluding the most recent five minutes
        // to avoid partially committed provider transaction statuses.
        JsonNode history =
                client.transactionHistory(
                        now.minus(Duration.ofHours(48)),
                        now.minus(Duration.ofMinutes(5)),
                        properties.merchantId(),
                        null);
        if (!history.isObject()
                || !history.path("results").isArray()
                || history.path("results").size() > 100)
            throw new SaspayException("SASPAY_PREFLIGHT_HISTORY_INVALID");
        for (JsonNode txn : history.path("results")) {
            if (!txn.isObject()
                    || !properties.merchantId().toString().equals(text(txn, "merchant"))
                    || !"XOF".equals(text(txn, "currency"))
                    || !Set.of("INBOUND", "OUTBOUND").contains(text(txn, "flow_direction"))
                    || !Set.of("PENDING", "SUCCESS", "FAILED", "CANCELLED")
                            .contains(text(txn, "status")))
                throw new SaspayException("SASPAY_PREFLIGHT_HISTORY_INVALID");
        }

        boolean moreHistory =
                !history.path("next").isNull() && !history.path("next").isMissingNode();
        String status;
        if (!balancesComplete || moreHistory) status = "INCOMPLETE_SAMPLE";
        else if (senegalWallets == 0) status = "NO_SN_XOF_BALANCE";
        else if (frozen) status = "WALLET_FROZEN";
        else status = "READ_ONLY_ACCESS_OK";

        return new Snapshot(
                now,
                status,
                history.path("results").size(),
                moreHistory,
                walletRows.size(),
                balancesComplete,
                senegalWallets,
                available,
                pending,
                frozen,
                "DIAGNOSTIC_NOT_ACCOUNTING_PROOF");
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).asText() : "";
    }

    private static BigDecimal money(JsonNode node, String field) {
        JsonNode raw = node.path(field);
        if (!raw.isTextual() && !raw.isNumber())
            throw new SaspayException("SASPAY_PREFLIGHT_BALANCES_INVALID");
        try {
            BigDecimal amount = new BigDecimal(raw.asText());
            if (amount.signum() < 0
                    || amount.precision() > 18
                    || amount.stripTrailingZeros().scale() > 2)
                throw new NumberFormatException("Invalid monetary precision");
            return amount;
        } catch (NumberFormatException invalid) {
            throw new SaspayException("SASPAY_PREFLIGHT_BALANCES_INVALID");
        }
    }
}
