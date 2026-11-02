package sn.groupia.directsales.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Offline Senegal ADM1 point-in-polygon lookup. Coordinates are WGS84 (lon,lat).
 * Data: geoBoundaries SEN ADM1, represented year 2019, Government of Senegal/OCHA,
 * CC BY 3.0 IGO. Geography is an approximation, not proof the buyer owns a GPS.
 */
@Component
public final class DirectRegionGeometry {
    private static final String SOURCE = "geoBoundaries-SEN-ADM1-2019";
    private static final double EDGE_MARGIN_DEGREES = 0.0001;
    private static final Map<String, String> NAMES =
            Map.ofEntries(
                    Map.entry("Dakar", "Dakar"),
                    Map.entry("Diourbel", "Diourbel"),
                    Map.entry("Fatick", "Fatick"),
                    Map.entry("Kaffrine", "Kaffrine"),
                    Map.entry("Kaolack", "Kaolack"),
                    Map.entry("Kolda", "Kolda"),
                    Map.entry("Louga", "Louga"),
                    Map.entry("Matam", "Matam"),
                    Map.entry("Saint Louis", "Saint-Louis"),
                    Map.entry("Sedhiou", "Sédhiou"),
                    Map.entry("Tambacounda", "Tambacounda"),
                    Map.entry("Thies", "Thiès"),
                    Map.entry("Ziguinchor", "Ziguinchor"),
                    Map.entry("Kedougou", "Kédougou"));

    private record Point(double lon, double lat) {}
    private record Bounds(double minLon, double minLat, double maxLon, double maxLat) {
        boolean near(Point p) {
            return p.lon() >= minLon - EDGE_MARGIN_DEGREES
                    && p.lon() <= maxLon + EDGE_MARGIN_DEGREES
                    && p.lat() >= minLat - EDGE_MARGIN_DEGREES
                    && p.lat() <= maxLat + EDGE_MARGIN_DEGREES;
        }
    }
    private record Polygon(List<List<Point>> rings, Bounds bounds) {}
    private record Region(String name, List<Polygon> polygons) {}
    public record Match(String status, String region, String source) {}
    private enum Containment { INSIDE, OUTSIDE, UNCERTAIN }

    private final List<Region> regions;

    public DirectRegionGeometry(ObjectMapper json) {
        try (var in = getClass().getResourceAsStream("/geo/senegal-regions-adm1.geojson")) {
            if (in == null) throw new IllegalStateException("Senegal ADM1 geometry is missing");
            JsonNode root = json.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (!"FeatureCollection".equals(root.path("type").asText())
                    || !root.path("features").isArray()
                    || root.path("features").size() != 14) {
                throw new IllegalStateException("Unexpected Senegal ADM1 data shape");
            }
            var parsed = new ArrayList<Region>();
            var unique = new HashSet<String>();
            for (var feature : root.path("features")) {
                String sourceName = feature.path("properties").path("shapeName").asText();
                String name = NAMES.get(sourceName);
                if (name == null || !unique.add(name)) {
                    throw new IllegalStateException("Unrecognized or duplicate ADM1 name: " + sourceName);
                }
                JsonNode geometry = feature.path("geometry");
                String type = geometry.path("type").asText();
                var polygons = new ArrayList<Polygon>();
                if ("Polygon".equals(type)) {
                    polygons.add(parsePolygon(geometry.path("coordinates")));
                } else if ("MultiPolygon".equals(type)) {
                    for (var polygon : geometry.path("coordinates")) {
                        polygons.add(parsePolygon(polygon));
                    }
                } else {
                    throw new IllegalStateException("Unsupported ADM1 geometry: " + type);
                }
                if (polygons.isEmpty()) throw new IllegalStateException("Empty ADM1 region");
                parsed.add(new Region(name, List.copyOf(polygons)));
            }
            if (!unique.equals(new HashSet<>(NAMES.values()))) {
                throw new IllegalStateException("Incomplete Senegal regions");
            }
            regions = List.copyOf(parsed);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load Senegal boundaries", ex);
        }
    }

    public Match locate(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            return new Match("INVALID", null, SOURCE);
        }
        Point point = new Point(longitude, latitude);
        String matched = null;
        for (Region region : regions) {
            for (Polygon polygon : region.polygons()) {
                if (!polygon.bounds().near(point)) continue;
                Containment containment = contains(polygon, point);
                if (containment == Containment.UNCERTAIN) {
                    return new Match("UNCERTAIN", null, SOURCE);
                }
                if (containment == Containment.INSIDE) {
                    if (matched != null && !matched.equals(region.name())) {
                        return new Match("UNCERTAIN", null, SOURCE);
                    }
                    matched = region.name();
                }
            }
        }
        return matched == null
                ? new Match("OUTSIDE_SENEGAL", null, SOURCE)
                : new Match("FOUND", matched, SOURCE);
    }

    private static Polygon parsePolygon(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalStateException("Polygon has no rings");
        }
        var rings = new ArrayList<List<Point>>();
        double minLon = 180, minLat = 90, maxLon = -180, maxLat = -90;
        for (JsonNode ring : node) {
            if (!ring.isArray() || ring.size() < 4 || ring.size() > 100000) {
                throw new IllegalStateException("Invalid ADM1 ring");
            }
            var points = new ArrayList<Point>();
            for (JsonNode pair : ring) {
                if (!pair.isArray() || pair.size() < 2
                        || !pair.get(0).isNumber() || !pair.get(1).isNumber()) {
                    throw new IllegalStateException("Invalid ADM1 vertex");
                }
                Point p = new Point(pair.get(0).asDouble(), pair.get(1).asDouble());
                if (!Double.isFinite(p.lon()) || !Double.isFinite(p.lat())
                        || p.lon() < -180 || p.lon() > 180 || p.lat() < -90 || p.lat() > 90) {
                    throw new IllegalStateException("Invalid ADM1 coordinate");
                }
                points.add(p);
                minLon = Math.min(minLon, p.lon());
                minLat = Math.min(minLat, p.lat());
                maxLon = Math.max(maxLon, p.lon());
                maxLat = Math.max(maxLat, p.lat());
            }
            rings.add(List.copyOf(points));
        }
        return new Polygon(List.copyOf(rings), new Bounds(minLon, minLat, maxLon, maxLat));
    }

    private static Containment contains(Polygon polygon, Point point) {
        // Odd-even crossings over each ring: the first ring is the outer shell;
        // subsequent rings are exclusion holes. Edge proximity is indeterminate.
        boolean inside = false;
        for (List<Point> ring : polygon.rings()) {
            boolean inRing = false;
            for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
                Point a = ring.get(j), b = ring.get(i);
                if (nearSegment(point, a, b)) return Containment.UNCERTAIN;
                if ((a.lat() > point.lat()) != (b.lat() > point.lat())
                        && point.lon() < (b.lon() - a.lon()) * (point.lat() - a.lat())
                                / (b.lat() - a.lat()) + a.lon()) {
                    inRing = !inRing;
                }
            }
            if (inRing) inside = !inside;
        }
        return inside ? Containment.INSIDE : Containment.OUTSIDE;
    }

    private static boolean nearSegment(Point p, Point a, Point b) {
        double dx = b.lon() - a.lon(), dy = b.lat() - a.lat();
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0 ? 0
                : Math.max(0, Math.min(1,
                        ((p.lon() - a.lon()) * dx + (p.lat() - a.lat()) * dy)
                                / lengthSquared));
        double distanceLon = p.lon() - (a.lon() + t * dx);
        double distanceLat = p.lat() - (a.lat() + t * dy);
        return distanceLon * distanceLon + distanceLat * distanceLat
                <= EDGE_MARGIN_DEGREES * EDGE_MARGIN_DEGREES;
    }
}
