package sn.groupia.directsales.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.organization.api.OrganizationAccess;
import sn.groupia.organization.api.OrganizationPermission;
import sn.groupia.shared.api.ApiException;
import tools.jackson.databind.ObjectMapper;

/**
 * Optional merchant-configured delivery coverage for Senegal. The customer-provided region is NOT a
 * verified GPS position; authoritative point-in-polygon checks will be added before the
 * checkout/payment rollout. The direct checkout remains disabled in production.
 */
@Service
public class DirectDeliveryPolicyService {
    public record Policy(String mode, List<String> allowedRegions, long version) {}

    private static final Set<String> SENEGAL_REGIONS =
            Set.of(
                    "Dakar",
                    "Diourbel",
                    "Fatick",
                    "Kaffrine",
                    "Kaolack",
                    "Kédougou",
                    "Kolda",
                    "Louga",
                    "Matam",
                    "Saint-Louis",
                    "Sédhiou",
                    "Tambacounda",
                    "Thiès",
                    "Ziguinchor");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OrganizationAccess access;
    private final DirectStorefrontService storefronts;
    private final Clock clock;

    public DirectDeliveryPolicyService(
            JdbcTemplate jdbc,
            ObjectMapper json,
            OrganizationAccess access,
            DirectStorefrontService storefronts,
            Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.access = access;
        this.storefronts = storefronts;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Policy manager(UUID organizationId) {
        access.requirePermission(organizationId, OrganizationPermission.VIEW_CATALOG);
        return read(requireStore(organizationId, false), organizationId);
    }

    @Transactional(readOnly = true)
    public Policy publicPolicy(UUID publicIdentifier) {
        // Enforce the same publication/active shop gate as the public product catalogue.
        storefronts.publicStore(publicIdentifier);
        var ids =
                jdbc.query(
                        "select id,organization_id from direct_storefronts where public_identifier=?",
                        (rs, row) ->
                                new UUID[] {
                                    rs.getObject("id", UUID.class),
                                    rs.getObject("organization_id", UUID.class)
                                },
                        publicIdentifier);
        if (ids.isEmpty()) {
            throw new ApiException(404, "DIRECT_STORE_UNAVAILABLE", "Boutique indisponible.");
        }
        return read(ids.getFirst()[0], ids.getFirst()[1]);
    }

    @Transactional
    public Policy save(UUID organizationId, Policy input) {
        access.requirePermission(organizationId, OrganizationPermission.MANAGE_CATALOG);
        if (input == null
                || input.version() < 0
                || input.mode() == null
                || !Set.of("ALL", "SELECTED", "DISABLED").contains(input.mode())
                || input.allowedRegions() == null) {
            throw invalid();
        }
        List<String> regions = validatedRegions(input.mode(), input.allowedRegions());
        // The storefront lock serializes concurrent edits, just like offer publication.
        UUID storefrontId = requireStore(organizationId, true);
        Policy current = read(storefrontId, organizationId);
        if (current.version() != input.version()) {
            throw new ApiException(
                    409,
                    "DIRECT_DELIVERY_POLICY_CONFLICT",
                    "Les zones ont été modifiées. Rechargez la page.");
        }
        if (current.version() == 0
                && jdbc.queryForObject(
                                "select count(*) from direct_delivery_policies"
                                        + " where organization_id=? and storefront_id=?",
                                Integer.class,
                                organizationId,
                                storefrontId)
                        == 0) {
            jdbc.update(
                    "insert into direct_delivery_policies"
                            + " (organization_id,storefront_id,mode,allowed_regions,version,updated_at)"
                            + " values (?,?,?,cast(? as jsonb),1,?)",
                    organizationId,
                    storefrontId,
                    input.mode(),
                    json.writeValueAsString(regions),
                    Timestamp.from(clock.instant()));
        } else {
            int updated =
                    jdbc.update(
                            "update direct_delivery_policies"
                                    + " set mode=?,allowed_regions=cast(? as jsonb),"
                                    + " version=version+1,updated_at=?"
                                    + " where organization_id=? and storefront_id=? and version=?",
                            input.mode(),
                            json.writeValueAsString(regions),
                            Timestamp.from(clock.instant()),
                            organizationId,
                            storefrontId,
                            input.version());
            if (updated != 1) {
                throw new ApiException(
                        409,
                        "DIRECT_DELIVERY_POLICY_CONFLICT",
                        "Les zones ont été modifiées. Rechargez la page.");
            }
        }
        return read(storefrontId, organizationId);
    }

    /** Preliminary region check only; not a substitute for server-verified GPS boundaries. */
    @Transactional(readOnly = true)
    public void requireDeliveryAllowed(UUID organizationId, UUID storefrontId, String region) {
        Policy policy = read(storefrontId, organizationId);
        if (policy.mode().equals("DISABLED")) {
            throw new ApiException(
                    409, "DIRECT_DELIVERY_DISABLED", "Cette boutique ne propose pas la livraison.");
        }
        if (policy.mode().equals("SELECTED") && !policy.allowedRegions().contains(region)) {
            throw new ApiException(
                    409,
                    "DIRECT_DELIVERY_OUTSIDE_ZONE",
                    "Cette boutique ne livre pas dans la région sélectionnée.");
        }
    }

    private UUID requireStore(UUID organizationId, boolean lock) {
        var matches =
                jdbc.query(
                        "select id from direct_storefronts where organization_id=?"
                                + (lock ? " for update" : ""),
                        (rs, row) -> rs.getObject("id", UUID.class),
                        organizationId);
        if (matches.isEmpty()) {
            throw new ApiException(
                    409, "DIRECT_STORE_REQUIRED", "Créez d'abord votre boutique directe.");
        }
        return matches.getFirst();
    }

    private Policy read(UUID storeId, UUID organizationId) {
        var results =
                jdbc.query(
                        "select mode,allowed_regions::text as regions,version"
                                + " from direct_delivery_policies"
                                + " where organization_id=? and storefront_id=?",
                        (rs, row) -> {
                            var regions = new ArrayList<String>();
                            var nodes = json.readTree(rs.getString("regions")).iterator();
                            while (nodes.hasNext()) regions.add(nodes.next().asText());
                            return new Policy(
                                    rs.getString("mode"),
                                    List.copyOf(regions),
                                    rs.getLong("version"));
                        },
                        organizationId,
                        storeId);
        return results.isEmpty() ? new Policy("DISABLED", List.of(), 0) : results.getFirst();
    }

    private List<String> validatedRegions(String mode, List<String> input) {
        if (!mode.equals("SELECTED")) {
            if (!input.isEmpty()) throw invalid();
            return List.of();
        }
        if (input.isEmpty() || input.size() > SENEGAL_REGIONS.size()) throw invalid();
        var seen = new HashSet<String>();
        for (String region : input) {
            if (region == null || !SENEGAL_REGIONS.contains(region) || !seen.add(region))
                throw invalid();
        }
        return seen.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private ApiException invalid() {
        return new ApiException(
                400, "INVALID_DIRECT_DELIVERY_POLICY", "Zones de livraison invalides.");
    }
}
