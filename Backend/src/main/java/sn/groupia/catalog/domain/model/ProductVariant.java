package sn.groupia.catalog.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "product_variants")
public class ProductVariant {
    @Id private UUID id;
    @Column(nullable = false, updatable = false) private UUID organizationId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;
    @Column(length = 64) private String sku;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> attributes;
    @Column(nullable = false, length = 67) private String canonicalVariantKey;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16) private SaleUnit saleUnit;
    @Column(columnDefinition = "numeric") private BigDecimal estimatedUnitWeightKg;
    @Column(columnDefinition = "numeric") private BigDecimal estimatedUnitVolumeM3;
    @Column(nullable = false) private boolean active;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    protected ProductVariant() {}

    static ProductVariant create(Product product, VariantSpecification specification, Instant now) {
        var variant = new ProductVariant();
        variant.id = UUID.randomUUID();
        variant.product = product;
        variant.organizationId = product.getOrganizationId();
        variant.active = true;
        variant.createdAt = now;
        variant.update(specification, now);
        return variant;
    }

    void update(VariantSpecification specification, Instant now) {
        sku = specification.sku();
        attributes = new TreeMap<>(specification.attributes());
        canonicalVariantKey = specification.canonicalVariantKey();
        saleUnit = specification.saleUnit();
        estimatedUnitWeightKg = specification.estimatedUnitWeightKg();
        estimatedUnitVolumeM3 = specification.estimatedUnitVolumeM3();
        updatedAt = now;
    }

    void deactivate(Instant now) { active = false; updatedAt = now; }
    public Product getProduct() { return product; }
    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getSku() { return sku; }
    public Map<String, String> getAttributes() { return Collections.unmodifiableMap(attributes); }
    public String getCanonicalVariantKey() { return canonicalVariantKey; }
    public SaleUnit getSaleUnit() { return saleUnit; }
    public BigDecimal getEstimatedUnitWeightKg() { return estimatedUnitWeightKg; }
    public BigDecimal getEstimatedUnitVolumeM3() { return estimatedUnitVolumeM3; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
