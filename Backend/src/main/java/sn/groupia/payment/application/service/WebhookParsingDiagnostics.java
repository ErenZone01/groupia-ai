package sn.groupia.payment.application.service;

import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Value-free, bounded inspection for parse failures only; never used as financial evidence. */
final class WebhookParsingDiagnostics {
    private static final JsonMapper JSON =
            JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final List<String> FIELDS =
            List.of(
                    "id",
                    "amount",
                    "currency",
                    "status",
                    "paymentReference",
                    "merchantReference",
                    "reference",
                    "type",
                    "event",
                    "timestamp");
    private static final Set<String> EXCLUDED =
            Set.of(
                    "customerobject",
                    "customer",
                    "checkouturl",
                    "headers",
                    "apikey",
                    "secret",
                    "webhooksecret",
                    "authorization");

    record Shape(
            String rootType,
            List<String> rootFields,
            String dataType,
            List<String> dataFields,
            Map<String, String> rootFieldTypes,
            Map<String, String> dataFieldTypes) {}

    static Shape inspect(byte[] payload) {
        try {
            var root = JSON.readTree(payload);
            if (root == null || root.isMissingNode()) {
                return unavailable();
            }
            var data = root.isObject() ? root.get("data") : null;
            return new Shape(
                    type(root), names(root), type(data), names(data), types(root), types(data));
        } catch (RuntimeException ignored) {
            return unavailable();
        }
    }

    private static Shape unavailable() {
        return new Shape("invalid", List.of(), "unavailable", List.of(), Map.of(), Map.of());
    }

    private static List<String> names(JsonNode node) {
        if (node == null || !node.isObject()) {
            return List.of();
        }
        var names = new ArrayList<String>();
        for (var field : node.properties()) {
            String name = field.getKey();
            if (EXCLUDED.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (names.size() == 40) {
                names.add("<truncated>");
                break;
            }
            // Field names are also untrusted: suppress identifiers resembling PII or log injection.
            names.add(FIELDS.contains(name) || name.equals("data") ? name : "<unknown-field>");
        }
        return List.copyOf(names);
    }

    private static Map<String, String> types(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        var result = new LinkedHashMap<String, String>();
        for (String field : FIELDS) {
            result.put(field, type(node.get(field)));
        }
        return Collections.unmodifiableMap(result);
    }

    private static String type(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "missing";
        }
        if (node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            return "object";
        }
        if (node.isArray()) {
            return "array";
        }
        if (node.isString()) {
            return "string";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        return "unknown";
    }
}
