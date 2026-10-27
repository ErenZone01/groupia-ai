package sn.groupia.ordering.domain.repository;

import java.util.*;
import sn.groupia.ordering.domain.model.*;

public interface Orders {
    List<Order> forDistribution(UUID organizationId, UUID campaignId);

    void flush();

    List<Order> forCompletion(UUID org, UUID campaign, boolean lock);

    List<Order> confirmedForShipping(UUID organizationId, UUID campaignId);

    Optional<Order> lock(UUID id);

    List<UUID> expiredCandidates(java.time.Instant now, UUID after, int limit);

    void lockIdempotency(UUID organizationId, String key);

    Optional<Order> findByKey(UUID organizationId, String key);

    void add(Customer customer, Order order);

    String nextOrderNumber();

    Optional<Order> findByTrackingHash(String hash);

    Optional<Order> find(UUID organizationId, UUID orderId);

    /** Product identities from the already-authorized orders, loaded in a single tenant-scoped query. */
    Map<UUID, List<UUID>> productPreviews(UUID organizationId, List<UUID> orderIds);

    Results list(UUID organizationId, UUID campaignId, OrderStatus status, int page, int size);

    record Results(List<Order> items, long totalElements, int totalPages) {}
}
