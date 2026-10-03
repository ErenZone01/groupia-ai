package sn.groupia.ordering.infrastructure.persistence;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import sn.groupia.ordering.domain.model.*;
interface JpaOrderRepository extends JpaRepository<Order, UUID> {
    @Query("select distinct o from CustomerOrder o left join fetch o.items where o.organizationId=:org and o.campaignId=:campaign and o.status=sn.groupia.ordering.domain.model.OrderStatus.CONFIRMED order by o.orderNumber")
    List<Order> shipping(UUID org, UUID campaign);
    Optional<Order> findByOrganizationIdAndCheckoutIdempotencyKey(UUID organizationId, String checkoutIdempotencyKey);
    Optional<Order> findByTrackingTokenHash(String hash);
    Optional<Order> findByOrganizationIdAndId(UUID organizationId, UUID id);
    @Query("""
        select o from CustomerOrder o where o.organizationId = :org
        and (:campaign is null or o.campaignId = :campaign) and (:status is null or o.status = :status)
        """)
    Page<Order> search(UUID org, UUID campaign, OrderStatus status, Pageable page);
}
