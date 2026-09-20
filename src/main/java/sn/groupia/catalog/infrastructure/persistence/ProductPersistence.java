package sn.groupia.catalog.infrastructure.persistence;

import java.util.*;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Repository;
import sn.groupia.catalog.domain.model.*;
import sn.groupia.catalog.domain.repository.Products;
import sn.groupia.shared.api.ApiException;

@Repository
class ProductPersistence implements Products {
    private final JpaProductRepository repository;
    ProductPersistence(JpaProductRepository repository) { this.repository = repository; }

    @Override
    public List<ProductVariant> findVariants(UUID organizationId, Set<UUID> variantIds) {
        return repository.findVariants(organizationId, variantIds);
    }
    @Override
    public Product save(Product product) {
        try { return repository.saveAndFlush(product); }
        catch (OptimisticLockingFailureException ex) {
            throw new ApiException(409, "PRODUCT_VERSION_CONFLICT", "Le produit a changé. Rechargez-le avant de réessayer.");
        } catch (DataIntegrityViolationException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException constraint) {
                    if ("uk_variants_product_key".equals(constraint.getConstraintName())) {
                        throw new ApiException(409, "DUPLICATE_VARIANT", "Cette combinaison de variante existe déjà.");
                    }
                    if ("uk_variants_organization_sku".equals(constraint.getConstraintName())) {
                        throw new ApiException(409, "SKU_ALREADY_EXISTS", "Ce SKU existe déjà dans cette organisation.");
                    }
                }
            }
            throw ex;
        }
    }

    @Override
    public Optional<Product> find(UUID organizationId, UUID productId) {
        return repository.findByOrganizationIdAndId(organizationId, productId);
    }

    @Override
    public ProductResults list(UUID organizationId, ProductStatus status, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        var results = status == null ? repository.findByOrganizationId(organizationId, pageable)
                : repository.findByOrganizationIdAndStatus(organizationId, status, pageable);
        return new ProductResults(results.getContent(), results.getTotalElements(), results.getTotalPages());
    }
}
