package sn.groupia.catalog.domain.repository;
import java.util.*;
import sn.groupia.catalog.domain.model.*;
public interface Products {
    Product save(Product product);
    Optional<Product> find(UUID organizationId, UUID productId);
    ProductResults list(UUID organizationId, ProductStatus status, int page, int size);
    record ProductResults(List<Product> items, long totalElements, int totalPages) {}
}
