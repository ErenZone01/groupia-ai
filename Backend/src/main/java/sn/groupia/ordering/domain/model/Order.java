package sn.groupia.ordering.domain.model;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import sn.groupia.grouping.api.dto.CheckoutQuote;
import sn.groupia.ordering.api.dto.CustomerContact;
import sn.groupia.shipping.api.ShippingCollectionMode;
import sn.groupia.shipping.api.dto.ShippingTerms;
import sn.groupia.shared.api.ApiException;

@Entity(name = "CustomerOrder") @Table(name = "orders")
public class Order {
    @Id private UUID id;
    @Column(nullable = false, updatable = false) private UUID organizationId;
    @Column(nullable = false, updatable = false) private UUID campaignId;
    @Column(nullable = false, updatable = false) private UUID customerId;
    @Column(nullable = false, length = 40, updatable = false) private String orderNumber;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private OrderStatus status;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private FulfillmentStatus fulfillmentStatus;
    @Column(nullable = false, length = 3) private String currency;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private CustomerContact customerContactSnapshot;
    @Column(nullable = false, columnDefinition = "numeric") private BigDecimal goodsSubtotal;
    @Column(nullable = false, columnDefinition = "numeric") private BigDecimal platformFeeAmount;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private Map<String, String> platformFeeRuleSnapshot;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private ShippingTerms shippingTermsSnapshot;
    @Column(columnDefinition = "numeric") private BigDecimal estimatedShippingAmount;
    @Column(nullable = false, columnDefinition = "numeric") private BigDecimal initialShippingChargeAmount;
    @Column(nullable = false, columnDefinition = "numeric") private BigDecimal initialAmountDue;
    @Column(nullable = false, length = 128, updatable = false) private String checkoutIdempotencyKey;
    @Column(nullable = false, length = 64, updatable = false) private String checkoutRequestFingerprint;
    @Column(nullable = false, length = 64) private String trackingTokenHash;
    @Column(nullable = false) private Instant paymentDeadline;
    @Column(nullable = false) private Instant trackingTokenExpiresAt;
    @Column(nullable = false) private Instant placedAt;
     private Instant trackingTokenRevokedAt;
     private Instant confirmedAt;
     private Instant cancelledAt;
     private Instant completedAt;
     private Instant pickedUpAt;
     private Instant readyForPickupAt;
    @Version private Long version;
    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    @OrderBy("lineNumber ASC") private List<OrderItem> items = new ArrayList<>();
    protected Order() {}
    public record Placement(CheckoutQuote quote, Customer customer, CustomerContact contact,
            String number, BigDecimal fee, Map<String, String> feeRule, BigDecimal estimate,
            String key, String fingerprint, String tokenHash, Instant now, Instant paymentDeadline, Instant tokenExpiry) {}
    public static Order place(Placement p) {
        var order = new Order();
        var q = p.quote();
        if (!q.orderable() || !"OPEN".equals(q.status()) || !"XOF".equals(q.currency())
                || q.offers().isEmpty() || !q.organizationId().equals(p.customer().getOrganizationId())) {
            throw new ApiException(409, "GROUPING_NOT_ORDERABLE", "Commande incompatible avec la campagne.");
        }
        order.id = UUID.randomUUID(); order.organizationId = q.organizationId(); order.campaignId = q.campaignId();
        order.customerId = p.customer().getId(); order.orderNumber = p.number();
        order.status = OrderStatus.PENDING_PAYMENT; order.fulfillmentStatus = FulfillmentStatus.NOT_READY;
        order.currency = q.currency(); order.customerContactSnapshot = p.contact();
        int line = 1;
        for (var offer : q.offers()) { order.items.add(OrderItem.snapshot(order, line++, offer)); }
        order.goodsSubtotal = order.items.stream().map(OrderItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        order.platformFeeAmount = p.fee(); order.platformFeeRuleSnapshot = Map.copyOf(p.feeRule());
        order.shippingTermsSnapshot = q.shippingTerms(); order.estimatedShippingAmount = p.estimate();
        order.initialShippingChargeAmount = q.shippingTerms().shippingCollectionMode() == ShippingCollectionMode.ESTIMATED_AT_CHECKOUT
                ? Objects.requireNonNull(p.estimate()) : BigDecimal.ZERO;
        order.initialAmountDue = order.goodsSubtotal.add(p.fee()).add(order.initialShippingChargeAmount);
        for (var amount : List.of(order.goodsSubtotal, p.fee(), order.initialShippingChargeAmount, order.initialAmountDue)) {
            if (amount.signum() < 0 || amount.stripTrailingZeros().scale() > 0
                    || amount.compareTo(new BigDecimal("1000000000000000000")) >= 0) {
                throw new ApiException(400, "ORDER_AMOUNT_OUT_OF_RANGE", "Le montant dépasse les limites de commande.");
            }
        }
        if (p.estimate() != null && (p.estimate().signum() < 0
                || p.estimate().compareTo(new BigDecimal("1000000000000000000")) >= 0)) {
            throw new ApiException(400, "ORDER_AMOUNT_OUT_OF_RANGE", "Estimation hors limite.");
        }
        order.checkoutIdempotencyKey = p.key(); order.checkoutRequestFingerprint = p.fingerprint();
        order.trackingTokenHash = p.tokenHash(); order.trackingTokenExpiresAt = p.tokenExpiry();
        order.placedAt = p.now(); order.paymentDeadline = p.paymentDeadline();
        return order;
    }
    public boolean confirmInitialPayment(BigDecimal amount,String currency,Instant settledAt) {
        if (initialAmountDue.compareTo(amount)!=0 || !this.currency.equals(currency) || settledAt.isBefore(placedAt)) {
            throw new IllegalStateException("Settlement does not match order");
        }
        if (status!=OrderStatus.PENDING_PAYMENT || !settledAt.isBefore(paymentDeadline)) { return false; }
        status=OrderStatus.CONFIRMED; confirmedAt=settledAt; return true;
    }
    public boolean expire(Instant now) {
        if(status!=OrderStatus.PENDING_PAYMENT || now.isBefore(paymentDeadline)){return false;}
        status=OrderStatus.EXPIRED; return true;
    }
    public boolean allowsTrackingAt(Instant now) {
        return trackingTokenRevokedAt == null && now.isBefore(trackingTokenExpiresAt);
    }
    public void requireVersion(long expected) {
        if(version==null || version!=expected)throw new ApiException(409,"ORDER_VERSION_CONFLICT","La commande a changé. Rechargez-la.");
    }
    public void readyForPickup(Instant now) {
        requireFulfillment(FulfillmentStatus.NOT_READY);
        fulfillmentStatus=FulfillmentStatus.READY_FOR_PICKUP;readyForPickupAt=now;
    }
    public void revokeReadiness() {
        requireFulfillment(FulfillmentStatus.READY_FOR_PICKUP);
        fulfillmentStatus=FulfillmentStatus.NOT_READY;readyForPickupAt=null;
    }
    public void pickUp(Instant now,boolean financiallySettled) {
        requireFulfillment(FulfillmentStatus.READY_FOR_PICKUP);
        fulfillmentStatus=FulfillmentStatus.PICKED_UP;pickedUpAt=now;
        if(financiallySettled){status=OrderStatus.COMPLETED;completedAt=now;}
    }
    private void requireFulfillment(FulfillmentStatus expected) {
        if(status!=OrderStatus.CONFIRMED || fulfillmentStatus!=expected)
            throw new ApiException(409,"ORDER_FULFILLMENT_CONFLICT","Cette action n'est plus disponible. Rechargez la commande.");
    }
    public Instant getReadyForPickupAt(){return readyForPickupAt;}
    public void completeAfterCreditResolution(Instant now){
        if(fulfillmentStatus==FulfillmentStatus.PICKED_UP && status==OrderStatus.CONFIRMED){status=OrderStatus.COMPLETED;completedAt=now;}
    }
    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getCampaignId() { return campaignId; }
    public UUID getCustomerId() { return customerId; }
    public String getOrderNumber() { return orderNumber; }
    public OrderStatus getStatus() { return status; }
    public FulfillmentStatus getFulfillmentStatus() { return fulfillmentStatus; }
    public String getCurrency() { return currency; }
    public CustomerContact getCustomerContactSnapshot() { return customerContactSnapshot; }
    public BigDecimal getGoodsSubtotal() { return goodsSubtotal; }
    public BigDecimal getPlatformFeeAmount() { return platformFeeAmount; }
    public Map<String, String> getPlatformFeeRuleSnapshot() { return Collections.unmodifiableMap(platformFeeRuleSnapshot); }
    public ShippingTerms getShippingTermsSnapshot() { return shippingTermsSnapshot; }
    public BigDecimal getEstimatedShippingAmount() { return estimatedShippingAmount; }
    public BigDecimal getInitialShippingChargeAmount() { return initialShippingChargeAmount; }
    public BigDecimal getInitialAmountDue() { return initialAmountDue; }
    public String getCheckoutIdempotencyKey() { return checkoutIdempotencyKey; }
    public String getCheckoutRequestFingerprint() { return checkoutRequestFingerprint; }
    public String getTrackingTokenHash() { return trackingTokenHash; }
    public Instant getPaymentDeadline() { return paymentDeadline; }
    public Instant getTrackingTokenExpiresAt() { return trackingTokenExpiresAt; }
    public Instant getPlacedAt() { return placedAt; }
    public Instant getTrackingTokenRevokedAt() { return trackingTokenRevokedAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getPickedUpAt() { return pickedUpAt; }
    public Long getVersion() { return version; }
    public List<OrderItem> getItems() { return Collections.unmodifiableList(items); }
}
