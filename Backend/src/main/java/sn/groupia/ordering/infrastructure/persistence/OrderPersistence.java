package sn.groupia.ordering.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import sn.groupia.ordering.domain.model.*;
import sn.groupia.ordering.domain.repository.Orders;

@Repository
class OrderPersistence implements Orders {
    private final JpaOrderRepository orders;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

    OrderPersistence(JpaOrderRepository orders, EntityManager entityManager, JdbcTemplate jdbc) {
        this.orders = orders;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    @Override
    public List<Order> forCompletion(UUID org, UUID campaign, boolean lock) {
        var ids =
                jdbc.query(
                        "select id from orders where organization_id=? and campaign_id=? order by id"
                                + (lock ? " for update" : ""),
                        (rs, row) -> rs.getObject(1, UUID.class),
                        org,
                        campaign);
        return ids.stream()
                .map(
                        id -> {
                            var o = entityManager.find(Order.class, id);
                            if (lock) entityManager.refresh(o);
                            return o;
                        })
                .toList();
    }

    @Override
    public void flush() {
        entityManager.flush();
    }

    @Override
    public List<Order> forDistribution(UUID org, UUID campaign) {
        return orders.distribution(org, campaign);
    }

    @Override
    public List<Order> confirmedForShipping(UUID org, UUID campaign) {
        return orders.shipping(org, campaign);
    }

    @Override
    public Optional<Order> lock(UUID id) {
        var order =
                entityManager.find(
                        Order.class, id, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (order != null) {
            entityManager.refresh(order, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        }
        return Optional.ofNullable(order);
    }

    @Override
    public List<UUID> expiredCandidates(java.time.Instant now, UUID after, int limit) {
        return jdbc.query(
                "select id from orders where status='PENDING_PAYMENT' and payment_deadline<=? and (cast(? as uuid) is null or id>?) order by id limit ?",
                (rs, row) -> rs.getObject(1, UUID.class),
                java.sql.Timestamp.from(now),
                after,
                after,
                limit);
    }

    @Override
    public void lockIdempotency(UUID org, String key) {
        try {
            long lock =
                    ByteBuffer.wrap(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    ("ordering:" + org + ":" + key)
                                                            .getBytes(StandardCharsets.UTF_8)))
                            .getLong();
            jdbc.query("select pg_advisory_xact_lock(?)", (rs, row) -> 0, lock);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    @Override
    public Optional<Order> findByKey(UUID org, String key) {
        return orders.findByOrganizationIdAndCheckoutIdempotencyKey(org, key);
    }

    @Override
    public void add(Customer customer, Order order) {
        entityManager.persist(customer);
        entityManager.persist(order);
        entityManager.flush();
    }

    @Override
    public String nextOrderNumber() {
        return "GRP-"
                + String.format(
                        Locale.ROOT,
                        "%012d",
                        jdbc.queryForObject("select nextval('order_number_seq')", Long.class));
    }

    @Override
    public Optional<Order> findByTrackingHash(String hash) {
        return orders.findByTrackingTokenHash(hash);
    }

    @Override
    public Optional<Order> find(UUID org, UUID id) {
        return orders.findByOrganizationIdAndId(org, id);
    }

    @Override
    public Map<UUID, List<UUID>> productPreviews(UUID org, List<UUID> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        // Query one page only. Both the order AND item belong to the selected boutique.
        var result = new HashMap<UUID, LinkedHashSet<UUID>>();
        new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query(
                """
                select oi.order_id, oi.product_id_snapshot
                from order_items oi
                join orders o on o.id=oi.order_id and o.organization_id=oi.organization_id
                where oi.organization_id=:org and oi.order_id in (:ids)
                order by oi.order_id, oi.line_number
                """,
                Map.of("org", org, "ids", orderIds),
                rs -> result.computeIfAbsent(rs.getObject(1, UUID.class), ignored -> new LinkedHashSet<>())
                        .add(rs.getObject(2, UUID.class)));
        var out = new HashMap<UUID, List<UUID>>();
        result.forEach((id, products) -> out.put(id, List.copyOf(products)));
        return out;
    }

    @Override
    public Results list(UUID org, UUID campaign, OrderStatus status, int page, int size) {
        var result =
                orders.search(
                        org,
                        campaign,
                        status,
                        PageRequest.of(
                                page,
                                size,
                                Sort.by(Sort.Order.desc("placedAt"), Sort.Order.asc("id"))));
        return new Results(result.getContent(), result.getTotalElements(), result.getTotalPages());
    }
}
