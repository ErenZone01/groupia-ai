package sn.groupia.payment.infrastructure.provider.saspay;

import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.*;

final class SaspayClient {
    private final RestClient http;
    private final ObjectMapper json;
    private final Clock clock;
    private volatile Instant retryAfter = Instant.MIN;

    SaspayClient(RestClient http, ObjectMapper json, Clock clock) {
        this.http = http;
        this.json = json;
        this.clock = clock;
    }

    static RestClient.Builder builder(SaspayProperties p) {
        var transport =
                HttpClient.newBuilder()
                        .connectTimeout(p.connectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        var factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(p.readTimeout());
        return RestClient.builder()
                .baseUrl(p.apiUrl().toString())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + p.apiKey())
                .defaultHeader("Accept", "application/json");
    }

    JsonNode rates() {
        return execute(http.get().uri("/pricing/my-rates/"), 200);
    }

    JsonNode createHostedCheckout(Map<String, Object> body) {
        // This endpoint deliberately has NO Idempotency-Key support. Groupia must
        // persist a single durable attempt before calling it and must not auto-retry
        // an uncertain POST, which could create a second payable session.
        return execute(
                http.post()
                        .uri("/checkout-sessions/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
                201);
    }

    JsonNode hostedCheckoutSessions(int page) {
        if (page < 1 || page > 3) throw new SaspayException("SASPAY_RECOVERY_PAGE_INVALID");
        return execute(http.get().uri("/checkout-sessions/?page={page}&page_size=100", page), 200);
    }

    JsonNode hostedCheckoutStatus(String sessionId) {
        return execute(http.get().uri("/checkout-sessions/{id}/status/", uuid(sessionId)), 200);
    }

    JsonNode hostedCheckoutDetail(String sessionId) {
        return execute(http.get().uri("/checkout-sessions/{id}/", uuid(sessionId)), 200);
    }

    JsonNode verify(String id) {
        uuid(id);
        return execute(http.get().uri("/payments/{id}/verify/", id), 200);
    }

    JsonNode initiate(Map<String, Object> body, String key) {
        uuid(key);
        return execute(
                http.post()
                        .uri("/payments/softpay/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .body(body),
                201);
    }

    static String uuid(String value) {
        if (value == null) throw new SaspayException("SASPAY_INVALID_IDENTIFIER");
        String canonical = value;
        // SasPay dashboard may display a UUID without dashes. Normalize both the
        // documented UUID form and this compact representation before correlating.
        if (value.matches("[0-9a-fA-F]{32}")) {
            canonical =
                    value.substring(0, 8)
                            + "-"
                            + value.substring(8, 12)
                            + "-"
                            + value.substring(12, 16)
                            + "-"
                            + value.substring(16, 20)
                            + "-"
                            + value.substring(20);
        }
        if (!canonical.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new SaspayException("SASPAY_INVALID_IDENTIFIER");
        return UUID.fromString(canonical).toString();
    }

    // Safe, closed vocabulary. No raw supplier codes or response bodies leave this adapter.
    private static String safeProviderCode(JsonNode response) {
        if (response == null || !response.isObject()) return "NONE";
        JsonNode code = response.path("error").path("code");
        if (!code.isTextual()) code = response.path("code");
        if (!code.isTextual()) return "NONE";
        return switch (code.asText()) {
            case "insufficient_balance",
                    "merchant_suspended",
                    "ip_not_whitelisted",
                    "payout_not_enabled",
                    "currency_country_mismatch",
                    "invalid_country",
                    "invalid_customer",
                    "invalid_method",
                    "no_route_available",
                    "wallet_frozen",
                    "not_found" -> code.asText();
            default -> "UNRECOGNIZED";
        };
    }

    private String safeFailureCode(java.io.InputStream body) {
        try {
            byte[] bytes = body.readNBytes(4097);
            if (bytes.length > 4096) return "NONE";
            return safeProviderCode(json.readTree(bytes));
        } catch (RuntimeException | java.io.IOException ex) {
            return "NONE";
        }
    }

    private JsonNode execute(RestClient.RequestHeadersSpec<?> request, int expected) {
        if (clock.instant().isBefore(retryAfter)) throw new SaspayException("SASPAY_RATE_LIMITED");
        try {
            return request.exchange(
                    (sent, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 429) {
                            long seconds = 60;
                            try {
                                seconds =
                                        Math.max(
                                                60,
                                                Long.parseLong(
                                                        response.getHeaders()
                                                                .getFirst("Retry-After")));
                            } catch (RuntimeException ignored) {
                                /* Conservative one-minute pause. */
                            }
                            retryAfter = clock.instant().plusSeconds(Math.min(seconds, 86400));
                        }
                        if (status != expected)
                            throw new SaspayException(
                                    "SASPAY_HTTP_" + status,
                                    status,
                                    safeFailureCode(response.getBody()));
                        byte[] body = response.getBody().readNBytes(262145);
                        if (body.length > 262144)
                            throw new SaspayException("SASPAY_RESPONSE_TOO_LARGE");
                        var node = json.readTree(body);
                        // Documentation examples omit the envelope; accept both documented
                        // representations.
                        if (node.has("success")) {
                            if (!node.path("success").isBoolean())
                                throw new SaspayException("SASPAY_INVALID_RESPONSE");
                            if (!node.path("success").booleanValue())
                                throw new SaspayException(
                                        "SASPAY_PROVIDER_REPORTED_FAILURE",
                                        status,
                                        safeProviderCode(node));
                            if (!node.has("data"))
                                throw new SaspayException("SASPAY_INVALID_RESPONSE");
                            node = node.get("data");
                        }
                        return node;
                    });
        } catch (SaspayException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new SaspayException("SASPAY_TRANSPORT_UNCERTAIN");
        }
    }
}
