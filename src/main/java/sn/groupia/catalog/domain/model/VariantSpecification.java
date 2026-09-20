package sn.groupia.catalog.domain.model;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.*;
import sn.groupia.shared.api.ApiException;

/** Validated catalogue data; canonicalization is versioned and server-owned. */
public record VariantSpecification(String sku, Map<String, String> attributes,
        String canonicalVariantKey, SaleUnit saleUnit,
        BigDecimal estimatedUnitWeightKg, BigDecimal estimatedUnitVolumeM3) {

    public VariantSpecification {
        attributes = normalizeAttributes(attributes);
        canonicalVariantKey = canonicalKey(attributes);
        sku = normalizeSku(sku);
        if (saleUnit == null) {
            throw new ApiException(400, "INVALID_SALE_UNIT", "Une unité de vente est requise.");
        }
        validateEstimate(estimatedUnitWeightKg);
        validateEstimate(estimatedUnitVolumeM3);
    }

    public static VariantSpecification from(String sku, Map<String, ?> attributes, String saleUnit,
            BigDecimal weight, BigDecimal volume) {
        SaleUnit unit;
        try { unit = SaleUnit.valueOf(saleUnit == null ? "UNIT" : saleUnit); }
        catch (IllegalArgumentException ex) {
            throw new ApiException(400, "INVALID_SALE_UNIT", "Unité de vente invalide.");
        }
        return new VariantSpecification(sku, normalizeAttributes(attributes), null, unit, weight, volume);
    }

    public static VariantSpecification defaultVariant() {
        return from(null, Map.of(), "UNIT", null, null);
    }

    private static Map<String, String> normalizeAttributes(Map<String, ?> source) {
        if (source == null) { return Map.of(); }
        if (source.size() > 16) { throw invalidAttributes(); }
        var normalized = new TreeMap<String, String>();
        source.forEach((key, value) -> {
            if (key == null || !(value instanceof String text)) { throw invalidAttributes(); }
            String normalizedKey = normalizeText(key).toLowerCase(Locale.ROOT);
            String normalizedValue = normalizeText(text).toLowerCase(Locale.ROOT);
            if (!normalizedKey.matches("[a-z][a-z0-9]{0,31}") || normalizedValue.isEmpty()
                    || normalizedValue.length() > 120
                    || normalizedValue.codePoints().anyMatch(Character::isISOControl)
                    || normalized.putIfAbsent(normalizedKey, normalizedValue) != null) {
                throw invalidAttributes();
            }
        });
        return Collections.unmodifiableMap(normalized);
    }

    private static String normalizeText(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("(?U)\\s+", " ").strip();
    }

    private static String canonicalKey(Map<String, String> attributes) {
        if (attributes.isEmpty()) { return "DEFAULT"; }
        var input = new StringBuilder();
        new TreeMap<>(attributes).forEach((key, value) -> {
            // Length prefixes make delimiters inside values unambiguous.
            input.append(key.length()).append(':').append(key);
            input.append(value.length()).append(':').append(value);
        });
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().getBytes(StandardCharsets.UTF_8));
            return "v1:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String normalizeSku(String value) {
        if (value == null || value.isBlank()) { return null; }
        String sku = Normalizer.normalize(value, Normalizer.Form.NFKC).strip().toUpperCase(Locale.ROOT);
        if (!sku.matches("[A-Z0-9][A-Z0-9._/-]{0,63}")) {
            throw new ApiException(400, "INVALID_SKU", "SKU invalide (64 caractères maximum, sans espace interne).");
        }
        return sku;
    }

    private static void validateEstimate(BigDecimal value) {
        if (value == null) { return; }
        BigDecimal normalized = value.stripTrailingZeros();
        if (value.signum() <= 0 || normalized.scale() > 18
                || normalized.precision() - normalized.scale() > 18) {
            throw new ApiException(400, "INVALID_VARIANT_ESTIMATE",
                    "Une estimation doit être positive, avec au plus 18 chiffres entiers et 18 décimales.");
        }
    }

    private static ApiException invalidAttributes() {
        return new ApiException(400, "INVALID_VARIANT_ATTRIBUTES", "Les attributs de variante sont invalides.");
    }
}
