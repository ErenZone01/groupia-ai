package sn.groupia.catalog.infrastructure.persistence;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import sn.groupia.catalog.domain.model.*;
interface JpaProductRepository extends JpaRepository<Product, UUID> {
    @Query("select v from ProductVariant v join fetch v.product p where v.organizationId = :organizationId and v.id in :variantIds")
    List<ProductVariant> findVariants(UUID organizationId, Set<UUID> variantIds);
    @EntityGraph(attributePaths = "variants")
    Optional<Product> findByOrganizationIdAndId(UUID organizationId, UUID id);
    Page<Product> findByOrganizationId(UUID organizationId, Pageable pageable);
    Page<Product> findByOrganizationIdAndStatus(UUID organizationId, ProductStatus status, Pageable pageable);
}
