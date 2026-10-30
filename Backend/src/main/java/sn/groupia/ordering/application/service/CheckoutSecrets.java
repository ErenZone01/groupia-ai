package sn.groupia.ordering.application.service;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Component;
import sn.groupia.grouping.api.dto.CheckoutSelection;
import sn.groupia.ordering.api.dto.CustomerContact;
import tools.jackson.databind.ObjectMapper;

@Component
class CheckoutSecrets {
    private final SecureRandom random = new SecureRandom();
    private final ObjectMapper json;

    CheckoutSecrets(ObjectMapper json) {
        this.json = json;
    }

    String token() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    String fingerprint(UUID publicId, CustomerContact contact, List<CheckoutSelection> lines) {
        // Fixed, versioned positional encoding; normalized/sorted input, never current
        // prices/configuration.
        var canonicalLines =
                lines.stream()
                        .map(
                                l ->
                                        List.of(
                                                l.offerId().toString(),
                                                Integer.toString(l.quantity()),
                                                l.expectedUnitPrice().toPlainString(),
                                                Long.toString(l.expectedPriceRevision())))
                        .toList();
        var base =
                Arrays.asList(
                        contact.firstName(),
                        contact.lastName(),
                        contact.phoneE164(),
                        contact.email());
        if (contact.deliveryAddress() == null) {
            // Preserve fingerprints of pre-address checkouts and in-flight retries.
            return hash(
                    json.writeValueAsString(
                            List.of("checkout-v1", publicId.toString(), base, canonicalLines)));
        }
        var address = contact.deliveryAddress();
        var location =
                Arrays.asList(
                        address.country(),
                        address.region(),
                        address.city(),
                        address.commune(),
                        address.neighborhood(),
                        address.details());
        return hash(
                json.writeValueAsString(
                        List.of(
                                "checkout-v2-address",
                                publicId.toString(),
                                base,
                                location,
                                canonicalLines)));
    }
}
