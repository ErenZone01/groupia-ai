package sn.groupia.ordering.domain.repository;
import java.util.*;
import sn.groupia.ordering.domain.model.*;
public interface Orders {
    void lockIdempotency(UUID organizationId, String key);
    Optional<Order> findByKey(UUID organizationId, String key);
    void add(Customer customer, Order order);
    String nextOrderNumber();
    Optional<Order> findByTrackingHash(String hash);
    Optional<Order> find(UUID organizationId, UUID orderId);
    Results list(UUID organizationId, UUID campaignId, OrderStatus status, int page, int size);
    record Results(List<Order> items, long totalElements, int totalPages) {}
}
