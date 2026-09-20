package sn.groupia.organization.domain.model;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "organizations")
public class Organization {
    @Id private UUID id;
    @Column(nullable = false, length = 160) private String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16) private OrganizationStatus status;
    @Column(nullable = false, length = 3) private String defaultCurrency;
    @Column(nullable = false, length = 2) private String countryCode;
    @Column(nullable = false, length = 64) private String timezone;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    @Version private Long version;

    protected Organization() {}

    public static Organization create(String name, Instant now) {
        if (name == null || name.isBlank() || name.strip().length() > 160) {
            throw new IllegalArgumentException("An organization requires a name of at most 160 characters.");
        }
        var organization = new Organization();
        organization.id = UUID.randomUUID();
        organization.name = name.strip();
        organization.status = OrganizationStatus.ACTIVE;
        organization.defaultCurrency = "XOF";
        organization.countryCode = "SN";
        organization.timezone = "Africa/Dakar";
        organization.createdAt = organization.updatedAt = now;
        return organization;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public OrganizationStatus getStatus() { return status; }
    public String getDefaultCurrency() { return defaultCurrency; }
    public String getCountryCode() { return countryCode; }
    public String getTimezone() { return timezone; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
