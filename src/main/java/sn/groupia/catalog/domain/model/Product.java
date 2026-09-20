package sn.groupia.catalog.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import jakarta.persistence.*;
import sn.groupia.shared.api.ApiException;

@Entity
@Table(name = "products")
public class Product {
    @Id private UUID id;
    @Column(nullable = false, updatable = false) private UUID organizationId;
    @Column(nullable = false, length = 160) private String name;
    @Column(length = 5000) private String description;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16) private ProductStatus status;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    @Version private Long version;
    @OneToMany(mappedBy = "product", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @OrderBy("createdAt ASC, id ASC")
    private List<ProductVariant> variants = new ArrayList<>();

    protected Product() {}

    public static Product create(UUID organizationId, String name, String description,
            List<VariantSpecification> specifications, Instant now) {
        var product = new Product();
        product.id = UUID.randomUUID();
        product.organizationId = Objects.requireNonNull(organizationId);
        product.status = ProductStatus.ACTIVE;
        product.setInformation(name, description);
        product.createdAt = product.updatedAt = now.truncatedTo(ChronoUnit.MICROS);
        var initial = specifications == null || specifications.isEmpty()
                ? List.of(VariantSpecification.defaultVariant()) : specifications;
        initial.forEach(specification -> {
            product.requireUniqueVariant(null, specification);
            product.variants.add(ProductVariant.create(product, specification, product.createdAt));
        });
        return product;
    }

    public void requireVersion(long expectedVersion) {
        if (version == null || version != expectedVersion) {
            throw new ApiException(409, "PRODUCT_VERSION_CONFLICT",
                    "Le produit a changé. Rechargez-le avant de réessayer.");
        }
    }

    public void updateInformation(String name, String description, Instant now) {
        requireEditable();
        setInformation(name, description);
        touch(now);
    }

    public void addVariant(VariantSpecification specification, Instant now) {
        requireEditable();
        requireUniqueVariant(null, specification);
        touch(now);
        variants.add(ProductVariant.create(this, specification, updatedAt));
    }

    public void updateVariant(UUID variantId, VariantSpecification specification, Instant now) {
        requireEditable();
        ProductVariant variant = variant(variantId);
        requireUniqueVariant(variantId, specification);
        touch(now);
        variant.update(specification, updatedAt);
    }

    public void deactivateVariant(UUID variantId, Instant now) {
        requireEditable();
        ProductVariant variant = variant(variantId);
        if (variant.isActive() && variants.stream().filter(ProductVariant::isActive).count() <= 1) {
            throw new ApiException(409, "INVALID_PRODUCT_STATE", "La dernière variante active ne peut pas être désactivée.");
        }
        touch(now);
        variant.deactivate(updatedAt);
    }

    public void archive(Instant now) {
        status = ProductStatus.ARCHIVED;
        touch(now);
    }

    private ProductVariant variant(UUID id) {
        return variants.stream().filter(variant -> variant.getId().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(404, "VARIANT_NOT_FOUND", "Variante introuvable pour ce produit."));
    }

    private void requireUniqueVariant(UUID excludedId, VariantSpecification specification) {
        if (variants.stream().anyMatch(variant -> !variant.getId().equals(excludedId)
                && variant.getCanonicalVariantKey().equals(specification.canonicalVariantKey()))) {
            throw new ApiException(409, "DUPLICATE_VARIANT", "Cette combinaison de variante existe déjà.");
        }
        if (specification.sku() != null && variants.stream().anyMatch(variant -> !variant.getId().equals(excludedId)
                && specification.sku().equals(variant.getSku()))) {
            throw new ApiException(409, "SKU_ALREADY_EXISTS", "Ce SKU existe déjà dans cette organisation.");
        }
    }

    private void requireEditable() {
        if (status == ProductStatus.ARCHIVED) {
            throw new ApiException(409, "PRODUCT_ARCHIVED", "Un produit archivé ne peut plus être modifié.");
        }
    }

    private void setInformation(String name, String description) {
        if (name == null || name.isBlank() || name.strip().length() > 160
                || name.codePoints().anyMatch(Character::isISOControl)
                || (description != null && (description.length() > 5000 || description.indexOf(0) >= 0))) {
            throw new ApiException(400, "INVALID_PRODUCT_INFORMATION", "Nom ou description du produit invalide.");
        }
        this.name = name.strip();
        this.description = description == null || description.isBlank() ? null : description.strip();
    }

    private void touch(Instant now) {
        // Every child mutation dirties the root, including two edits in the same clock tick.
        Instant candidate = now.truncatedTo(ChronoUnit.MICROS);
        updatedAt = candidate.isAfter(updatedAt) ? candidate : updatedAt.plus(1, ChronoUnit.MICROS);
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public ProductStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
    public List<ProductVariant> getVariants() { return Collections.unmodifiableList(variants); }
}

