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
        if (value == null
                || !value.matches(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new SaspayException("SASPAY_INVALID_IDENTIFIER");
        return value;
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
                        if (status != expected) throw new SaspayException("SASPAY_HTTP_" + status);
                        byte[] body = response.getBody().readNBytes(262145);
                        if (body.length > 262144)
                            throw new SaspayException("SASPAY_RESPONSE_TOO_LARGE");
                        var node = json.readTree(body);
                        // Documentation examples omit the envelope; accept both documented
                        // representations.
                        if (node.has("success")) {
                            if (!node.path("success").isBoolean()
                                    || !node.path("success").booleanValue()
                                    || !node.has("data"))
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
