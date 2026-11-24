package sn.groupia.payment.infrastructure.provider.saspay;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only provider-history comparison. Never confirms a payment, releases a reserve, changes a
 * ledger, sends a POST or treats a provider listing as independent settlement proof.
 */
@Component
@ConditionalOnProperty(
        name = {"groupia.payment.saspay.enabled", "groupia.payment.saspay.history-audit.enabled"},
        havingValue = "true")
final class SaspayHistoryAudit {
    private static final Logger log = LoggerFactory.getLogger(SaspayHistoryAudit.class);
    private static final int MAX_PAGES = 30;
    private final SaspayClient client;
    private final LedgerLookup ledger;
    private final SaspayProperties properties;
    private final Clock clock;

    record Evidence(
            String direction,
            String status,
            BigDecimal amount,
            String currency,
            BigDecimal fee,
            BigDecimal charged,
            boolean reviewRequired) {}

    record Reference(String transaction, String state) {}

    @FunctionalInterface
    interface LedgerLookup {
        List<Evidence> find(String providerAccount, String transaction);

        default List<Reference> references(String account, Instant from, Instant until) {
            return List.of();
        }
    }

    record Result(int pages, int transactions, int linked, int unmatched, int differences) {}

    @Autowired
    SaspayHistoryAudit(
            SaspayProperties properties, ObjectMapper json, Clock clock, JdbcTemplate jdbc) {
        this(
                new SaspayClient(SaspayClient.builder(properties).build(), json, clock),
                sqlLookup(jdbc),
                properties,
                clock);
    }

    SaspayHistoryAudit(
            SaspayClient client, LedgerLookup ledger, SaspayProperties properties, Clock clock) {
        this.client = client;
        this.ledger = ledger;
        this.properties = properties;
        this.clock = clock;
    }

    static LedgerLookup sqlLookup(JdbcTemplate jdbc) {
        return new LedgerLookup() {
            @Override
            public List<Evidence> find(String account, String transaction) {
                return jdbc.query(
                        """
                        SELECT 'INBOUND' AS direction, status, expected_amount AS amount, currency,
                            provider_collection_fee AS fee, provider_customer_charged AS charged,
                            reconciliation_required
                        FROM payments
                        WHERE provider='SASPAY' AND provider_account_reference=?
                            AND provider_transaction_id=?
                        UNION ALL
                        SELECT 'OUTBOUND', status, amount, currency, NULL::numeric, NULL::numeric,
                            reconciliation_required
                        FROM merchant_payouts
                        WHERE provider='SASPAY' AND provider_account_reference=?
                            AND provider_transaction_id=?
                        """,
                        (rs, row) ->
                                new Evidence(
                                        rs.getString("direction"),
                                        rs.getString("status"),
                                        rs.getBigDecimal("amount"),
                                        rs.getString("currency"),
                                        rs.getBigDecimal("fee"),
                                        rs.getBigDecimal("charged"),
                                        rs.getBoolean("reconciliation_required")),
                        account,
                        transaction,
                        account,
                        transaction);
            }

            @Override
            public List<Reference> references(String account, Instant from, Instant until) {
                // The local initiation window is not a provider creation-time proof.
                // Absence is an investigation signal, never evidence of non-execution.
                return jdbc.query(
                        """
                        SELECT provider_transaction_id, concat_ws(':',status,expected_amount,currency,
                            reconciliation_required,reconciliation_reason) AS state
                        FROM payments WHERE provider='SASPAY' AND provider_account_reference=?
                            AND provider_transaction_id IS NOT NULL AND initiated_at>=? AND initiated_at<?
                        UNION ALL
                        SELECT provider_transaction_id, concat_ws(':',status,amount,currency,
                            reconciliation_required,reconciliation_reason)
                        FROM merchant_payouts WHERE provider='SASPAY' AND provider_account_reference=?
                            AND provider_transaction_id IS NOT NULL AND requested_at>=? AND requested_at<?
                        LIMIT 3001
                        """,
                        (rs, row) -> new Reference(rs.getString(1), rs.getString(2)),
                        account,
                        java.sql.Timestamp.from(from),
                        java.sql.Timestamp.from(until),
                        account,
                        java.sql.Timestamp.from(from),
                        java.sql.Timestamp.from(until));
            }
        };
    }

    @FunctionalInterface
    interface IssueSink {
        void report(String code, String transaction);

        default void missing(Reference reference) {
            report("INTERNAL_REFERENCE_NOT_OBSERVED", reference.transaction());
        }
    }

    /** Default log-only comparison retained for diagnostics and existing contract tests. */
    Result compare(Instant from, Instant until) {
        return compare(from, until, SaspayHistoryAudit::issue);
    }

    /** An incomplete cursor traversal is never reported as a successful audit. */
    Result compare(Instant from, Instant until, IssueSink issueSink) {
        Objects.requireNonNull(issueSink, "issueSink");
        if (from == null
                || until == null
                || !from.isBefore(until)
                || Duration.between(from, until).compareTo(Duration.ofDays(7)) > 0)
            throw new SaspayException("SASPAY_HISTORY_WINDOW_INVALID");
        String cursor = null;
        Set<String> cursors = new HashSet<>();
        Set<String> transactions = new HashSet<>();
        int linked = 0, unmatched = 0, differences = 0;
        String account = properties.merchantId().toString();
        // Capture before GETs so concurrent new initiations cannot become false misses.
        List<Reference> expected = ledger.references(account, from, until);
        if (expected.size() > 3000)
            throw new SaspayException("SASPAY_INTERNAL_HISTORY_NOT_EXHAUSTIVE");
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonNode result =
                    client.transactionHistory(from, until, properties.merchantId(), cursor);
            if (!result.isObject() || !result.path("results").isArray())
                throw new SaspayException("SASPAY_HISTORY_PAGE_INVALID");
            for (JsonNode item : result.path("results")) {
                String id = string(item, "id");
                String canonical;
                try {
                    canonical = SaspayClient.uuid(id);
                } catch (SaspayException invalid) {
                    differences++;
                    issueSink.report("INVALID_TRANSACTION_ID", id);
                    continue;
                }
                if (!transactions.add(canonical)) {
                    differences++;
                    issueSink.report("DUPLICATE_TRANSACTION", canonical);
                    continue;
                }
                if (!account.equals(string(item, "merchant"))) {
                    differences++;
                    issueSink.report("MERCHANT_MISMATCH", canonical);
                    continue;
                }
                String direction = string(item, "flow_direction");
                String type = string(item, "transaction_type");
                if (!(("INBOUND".equals(direction) && "PAIEMENT".equals(type))
                        || ("OUTBOUND".equals(direction)
                                && Set.of("RETRAIT", "TRANSFERT").contains(type)))) {
                    differences++;
                    issueSink.report("UNSUPPORTED_FLOW", canonical);
                    continue;
                }
                List<Evidence> found = ledger.find(account, canonical);
                if (found.isEmpty()) {
                    unmatched++;
                    issueSink.report("UNMATCHED_TRANSACTION", canonical);
                    continue;
                }
                if (found.size() != 1) {
                    differences++;
                    issueSink.report("DUPLICATE_GROUPIA_REFERENCE", canonical);
                    continue;
                }
                linked++;
                try {
                    if (!matches(item, found.getFirst(), direction)) {
                        differences++;
                        issueSink.report("FINANCIAL_OR_STATUS_MISMATCH", canonical);
                    }
                } catch (RuntimeException malformed) {
                    differences++;
                    issueSink.report("INVALID_FINANCIAL_FIELDS", canonical);
                }
            }
            String next = nextCursor(result.path("next"));
            if (next == null) {
                for (Reference reference : expected) {
                    String canonical;
                    try {
                        canonical = SaspayClient.uuid(reference.transaction());
                    } catch (SaspayException invalid) {
                        differences++;
                        issueSink.report(
                                "INVALID_INTERNAL_TRANSACTION_ID", reference.transaction());
                        continue;
                    }
                    if (!transactions.contains(canonical)) {
                        differences++;
                        issueSink.missing(reference);
                    }
                }
                return new Result(page, transactions.size(), linked, unmatched, differences);
            }
            if (!cursors.add(next)) throw new SaspayException("SASPAY_HISTORY_CURSOR_LOOP");
            if (page == MAX_PAGES) throw new SaspayException("SASPAY_HISTORY_NOT_EXHAUSTIVE");
            cursor = next;
        }
        throw new SaspayException("SASPAY_HISTORY_NOT_EXHAUSTIVE");
    }

    private static boolean matches(JsonNode item, Evidence local, String direction) {
        String status = string(item, "status");
        String requiredStatus =
                switch (status == null ? "" : status) {
                    case "SUCCESS" -> "SUCCEEDED";
                    case "PENDING" -> "PENDING";
                    case "FAILED" -> "FAILED";
                    case "CANCELLED" -> "CANCELLED";
                    default -> "";
                };
        if (requiredStatus.isEmpty()
                || !direction.equals(local.direction())
                || !"XOF".equals(string(item, "currency"))
                || !"XOF".equals(local.currency())
                || local.reviewRequired()
                || local.amount() == null
                || local.amount().compareTo(money(item, "requested_amount")) != 0
                || !requiredStatus.equals(local.status())) return false;

        if (!"SUCCESS".equals(status)) return true;
        if ("INBOUND".equals(direction)) {
            BigDecimal principal = money(item, "requested_amount");
            BigDecimal fee = money(item, "client_fee");
            BigDecimal charged = money(item, "debited_amount");
            return "ADD_ON".equals(string(item, "fee_charge_mode"))
                    && principal.compareTo(money(item, "net_amount")) == 0
                    && principal.add(fee).compareTo(charged) == 0
                    && (local.fee() == null || fee.compareTo(local.fee()) == 0)
                    && (local.charged() == null || charged.compareTo(local.charged()) == 0);
        }
        // Payout fee/net evidence is checked by the existing two-GET payout verifier.
        return true;
    }

    private String nextCursor(JsonNode next) {
        if (next.isMissingNode() || next.isNull()) return null;
        if (!next.isTextual() || next.asText().isBlank())
            throw new SaspayException("SASPAY_HISTORY_NEXT_INVALID");
        URI url;
        try {
            url = URI.create(next.asText());
        } catch (IllegalArgumentException invalid) {
            throw new SaspayException("SASPAY_HISTORY_NEXT_INVALID");
        }
        URI base = properties.apiUrl();
        if (!"https".equals(url.getScheme())
                || !base.getHost().equals(url.getHost())
                || url.getPort() != -1
                || url.getUserInfo() != null
                || url.getFragment() != null
                || !(base.getPath() + "/transactions/").equals(url.getPath())
                || url.getRawQuery() == null)
            throw new SaspayException("SASPAY_HISTORY_NEXT_INVALID");
        String cursor = null;
        for (String part : url.getRawQuery().split("&")) {
            if (!part.startsWith("cursor=")) continue;
            if (cursor != null) throw new SaspayException("SASPAY_HISTORY_NEXT_INVALID");
            cursor = URLDecoder.decode(part.substring(7), StandardCharsets.UTF_8);
        }
        if (cursor == null || !cursor.matches("[A-Za-z0-9_\\-/+=]{1,512}"))
            throw new SaspayException("SASPAY_HISTORY_NEXT_INVALID");
        return cursor;
    }

    private static BigDecimal money(JsonNode node, String field) {
        JsonNode raw = node.path(field);
        if (!raw.isTextual() && !raw.isNumber())
            throw new IllegalArgumentException("Missing amount");
        BigDecimal amount = new BigDecimal(raw.asText());
        if (amount.signum() < 0
                || amount.precision() > 18
                || amount.stripTrailingZeros().scale() > 2)
            throw new IllegalArgumentException("Invalid amount");
        return amount;
    }

    private static String string(JsonNode node, String field) {
        JsonNode raw = node.path(field);
        return raw.isTextual() ? raw.asText() : null;
    }

    private static void issue(String code, String transaction) {
        String fingerprint = "unknown";
        if (transaction != null) {
            try {
                byte[] digest =
                        MessageDigest.getInstance("SHA-256")
                                .digest(transaction.getBytes(StandardCharsets.UTF_8));
                fingerprint = HexFormat.of().formatHex(digest, 0, 8);
            } catch (java.security.NoSuchAlgorithmException unavailable) {
                // SHA-256 is always provided by supported Java runtimes.
            }
        }
        log.warn("SasPay history difference code={} fingerprint={}", code, fingerprint);
    }
}
