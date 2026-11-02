package sn.groupia.directsales.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Cross-module recovery contract. Caller MUST authenticate a verified phone session. */
public interface DirectOrderRecovery {
    record Entry(
            String orderNumber,
            UUID publicIdentifier,
            String status,
            String fulfillmentMethod,
            String currency,
            Long acceptedTotalXof,
            Instant placedAt) {}

    record Page(List<Entry> items, long total, int page, int size) {}

    /** Issued token is a bearer secret: never log or retain it in another service. */
    record Rotated(String orderNumber, UUID publicIdentifier, String token) {
        @Override
        public String toString() {
            return "Rotated[redacted]";
        }
    }

    Page forVerifiedPhone(String normalizedPhone, int page, int size);

    Rotated rotateForVerifiedPhone(String normalizedPhone, String orderNumber);
}
