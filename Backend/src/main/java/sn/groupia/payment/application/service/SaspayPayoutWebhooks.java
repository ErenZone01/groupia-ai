package sn.groupia.payment.application.service;

import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import sn.groupia.payment.infrastructure.persistence.PayoutNotificationStore;
import sn.groupia.shared.api.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Payout events share the payment URL, but must never enter the pay-in allocation path. */
@Service
public class SaspayPayoutWebhooks {
    private final ObjectMapper json;
    private final ProviderRegistry providers;
    private final PayoutNotificationStore store;

    public SaspayPayoutWebhooks(
            ObjectMapper json, ProviderRegistry providers, PayoutNotificationStore store) {
        this.json = json;
        this.providers = providers;
        this.store = store;
    }

    public boolean recognizes(String provider, byte[] payload) {
        if (!"SASPAY".equalsIgnoreCase(provider)) return false;
        try {
            return "RETRAIT".equals(json.readTree(payload).path("data").path("type").asText());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public UUID receive(byte[] payload, Map<String, String> headers) {
        var provider = providers.require("SASPAY");
        boolean authentic;
        try {
            authentic = provider.verifyAuthenticity(payload, headers);
        } catch (RuntimeException ex) {
            authentic = false;
        }
        UUID transaction = null;
        String error = authentic ? null : "PAYMENT_AUTHENTICITY_FAILED";
        if (authentic) {
            try {
                JsonNode root = json.readTree(payload);
                String event = root.path("event").asText();
                String status = root.path("data").path("status").asText();
                String expected =
                        switch (status) {
                            case "SUCCESS" -> "transaction.success";
                            case "FAILED" -> "transaction.failed";
                            case "CANCELLED" -> "transaction.cancelled";
                            case "PENDING" -> "transaction.created";
                            default -> "";
                        };
                String id = root.path("data").path("id").asText();
                if (id.matches("[0-9a-fA-F]{32}"))
                    id =
                            id.substring(0, 8)
                                    + "-"
                                    + id.substring(8, 12)
                                    + "-"
                                    + id.substring(12, 16)
                                    + "-"
                                    + id.substring(16, 20)
                                    + "-"
                                    + id.substring(20);
                if (!id.matches(
                                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                        || expected.isEmpty()
                        || !expected.equals(event)
                        || !"RETRAIT".equals(root.path("data").path("type").asText()))
                    throw new IllegalArgumentException();
                transaction = UUID.fromString(id);
            } catch (RuntimeException ex) {
                error = "PAYOUT_NOTIFICATION_INVALID";
            }
        }
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
        UUID receipt =
                store.receive(provider.accountReference(), hash, authentic, transaction, error);
        if (!authentic)
            throw new ApiException(
                    401, "PAYMENT_AUTHENTICITY_FAILED", "Authenticité du webhook invalide.");
        return receipt;
    }
}
