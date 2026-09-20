package sn.groupia.catalog.domain.repository;
import java.util.*;
import sn.groupia.catalog.domain.model.*;
public interface Products {
    Product save(Product product);
    List<ProductVariant> findVariants(UUID organizationId, Set<UUID> variantIds);
    Optional<Product> find(UUID organizationId, UUID productId);
    ProductResults list(UUID organizationId, ProductStatus status, int page, int size);
    record ProductResults(List<Product> items, long totalElements, int totalPages) {}
}
