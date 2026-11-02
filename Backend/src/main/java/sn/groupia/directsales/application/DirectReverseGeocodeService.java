package sn.groupia.directsales.application;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import sn.groupia.shared.api.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Optional best-effort address suggestions. Geoapify estimates are never proof of location
 * or delivery eligibility. No coordinates, request identity, or provider response are persisted.
 */
@Service
public class DirectReverseGeocodeService {
    public record Suggestion(
            String status,
            String countryCode,
            String region,
            String city,
            String neighborhood,
            String formatted,
            String provider) {}

    private static final Map<String, String> REGIONS =
            Map.ofEntries(
                    Map.entry("dakar", "Dakar"),
                    Map.entry("diourbel", "Diourbel"),
                    Map.entry("fatick", "Fatick"),
                    Map.entry("kaffrine", "Kaffrine"),
                    Map.entry("kaolack", "Kaolack"),
                    Map.entry("kedougou", "Kédougou"),
                    Map.entry("kolda", "Kolda"),
                    Map.entry("louga", "Louga"),
                    Map.entry("matam", "Matam"),
                    Map.entry("saint louis", "Saint-Louis"),
                    Map.entry("sedhiou", "Sédhiou"),
                    Map.entry("tambacounda", "Tambacounda"),
                    Map.entry("thies", "Thiès"),
                    Map.entry("ziguinchor", "Ziguinchor"));

    private static final Suggestion UNAVAILABLE =
            new Suggestion("UNAVAILABLE", null, null, null, null, null, null);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DirectStorefrontService stores;
    private final Clock clock;
    private final HttpClient client;
    private final boolean enabled;
    private final String apiKey;

    public DirectReverseGeocodeService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            DirectStorefrontService stores,
            Clock clock,
            @Value("${groupia.geoapify.enabled:false}") boolean enabled,
            @Value("${groupia.geoapify.api-key:}") String apiKey) {
        this.jdbc = jdbc;
        this.json = json;
        this.stores = stores;
        this.clock = clock;
        this.enabled = enabled;
        this.apiKey = apiKey;
        if (enabled && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalStateException("Geoapify key required if geocoding is enabled");
        }
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(2))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    public Suggestion suggest(UUID publicIdentifier, double latitude, double longitude) {
        stores.publicStore(publicIdentifier);
        if (!Double.isFinite(latitude)
                || !Double.isFinite(longitude)
                || latitude < -90
                || latitude > 90
                || longitude < -180
                || longitude > 180) {
            throw new ApiException(400, "INVALID_GEO_POINT", "Position géographique invalide.");
        }
        if (!enabled) return UNAVAILABLE;

        // Global and per-second shared database limits; safe across app instances.
        // Below the free Geoapify limits, not a per-client anti-bot replacement.
        Instant now = clock.instant();
        var day = now.atZone(ZoneOffset.UTC).toLocalDate();
        if (!consume("day:" + day, day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC), 2400)
                || !consume(
                        "second:" + now.truncatedTo(ChronoUnit.SECONDS).getEpochSecond(),
                        now.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1),
                        3)) {
            throw new ApiException(
                    429, "GEOCODING_QUOTA_REACHED", "La recherche d'adresse est temporairement indisponible.");
        }
        URI uri =
                URI.create(
                        "https://api.geoapify.com/v1/geocode/reverse?lat="
                                + latitude
                                + "&lon="
                                + longitude
                                + "&limit=1&format=json&lang=fr&apiKey="
                                + URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
        HttpRequest request =
                HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(4))
                        .header("Accept", "application/json")
                        .GET()
                        .build();
        try {
            HttpResponse<String> response =
                    client.send(
                            request,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || response.body().length() > 65536) {
                return UNAVAILABLE;
            }
            return parseResponse(json.readTree(response.body()));
        } catch (IOException ex) {
            return UNAVAILABLE;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return UNAVAILABLE;
        } catch (RuntimeException ex) {
            // Provider response failures must not prevent manually entering an address.
            return UNAVAILABLE;
        }
    }

    private boolean consume(String bucket, Instant expiresAt, int max) {
        var results =
                jdbc.query(
                        "insert into direct_geocode_quotas (bucket,used,expires_at)"
                                + " values (?,1,?) on conflict (bucket)"
                                + " do update set used=direct_geocode_quotas.used+1"
                                + " where direct_geocode_quotas.used < ? returning used",
                        (rs, index) -> rs.getInt(1),
                        bucket,
                        Timestamp.from(expiresAt),
                        max);
        return !results.isEmpty();
    }

    @Scheduled(cron = "0 21 3 * * *", zone = "UTC")
    public void cleanupExpiredQuotas() {
        jdbc.update(
                "delete from direct_geocode_quotas where expires_at < ?",
                Timestamp.from(clock.instant().minus(2, ChronoUnit.DAYS)));
    }

    /** Does not infer missing fields or equate a provider's district to a Senegalese commune. */
    public static Suggestion parseResponse(JsonNode payload) {
        JsonNode results = payload.path("results");
        if (!results.isArray() || results.isEmpty()) {
            return new Suggestion("NO_RESULT", null, null, null, null, null, "geoapify");
        }
        JsonNode result = results.get(0);
        String code = field(result, "country_code");
        String state = field(result, "state");
        String normalized = state == null ? "" : normalize(state);
        String region = "sn".equalsIgnoreCase(code) ? REGIONS.get(normalized) : null;
        return new Suggestion(
                "FOUND",
                code == null ? null : code.toUpperCase(Locale.ROOT),
                region,
                field(result, "city"),
                first(field(result, "suburb"), field(result, "district")),
                field(result, "formatted"),
                "geoapify");
    }

    private static String first(String left, String right) {
        return left == null ? right : left;
    }

    private static String field(JsonNode data, String key) {
        JsonNode value = data.path(key);
        if (!value.isTextual()) return null;
        String text = value.asText().trim();
        return text.isBlank() ? null : text.substring(0, Math.min(160, text.length()));
    }

    private static String normalize(String value) {
        String text = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('-', ' ').replaceAll("\\s+", " ").trim();
        text = text.replaceFirst("^region (de |du |d )?", "");
        return text.replaceFirst(" region$", "").trim();
    }
}
