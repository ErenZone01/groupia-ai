package sn.groupia.directsales.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import sn.groupia.shared.api.ApiException;

/**
 * Public preview of configured delivery coverage for a submitted WGS84 point.
 * It does not attest the buyer's physical location and cannot authorize payment.
 */
@Service
public class DirectDeliveryCoverageService {
    public record Coverage(
            String status,
            String region,
            boolean preliminaryAllowed,
            String boundarySource) {}

    private final DirectDeliveryPolicyService policies;
    private final DirectRegionGeometry regions;

    public DirectDeliveryCoverageService(
            DirectDeliveryPolicyService policies, DirectRegionGeometry regions) {
        this.policies = policies;
        this.regions = regions;
    }

    public Coverage evaluate(UUID publicIdentifier, Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            throw new ApiException(400, "INVALID_GEO_POINT", "Position géographique invalide.");
        }
        // A published-store gate is required even if the point is not in Senegal.
        var policy = policies.publicPolicy(publicIdentifier);
        var point = regions.locate(latitude, longitude);
        if (point.status().equals("INVALID")) {
            throw new ApiException(400, "INVALID_GEO_POINT", "Position géographique invalide.");
        }
        if (policy.mode().equals("DISABLED")) {
            return new Coverage("DELIVERY_DISABLED", point.region(), false, point.source());
        }
        if (!point.status().equals("FOUND")) {
            return new Coverage(point.status(), null, false, point.source());
        }
        boolean allowed =
                policy.mode().equals("ALL")
                        || (policy.mode().equals("SELECTED")
                                && policy.allowedRegions().contains(point.region()));
        return new Coverage(
                allowed ? "INSIDE_ZONE" : "OUTSIDE_ZONE",
                point.region(),
                allowed,
                point.source());
    }
}
