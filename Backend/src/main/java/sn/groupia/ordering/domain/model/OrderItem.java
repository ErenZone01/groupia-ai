package sn.groupia.ordering.domain.model;
import java.math.BigDecimal;
import java.util.*;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import sn.groupia.grouping.api.dto.CheckoutOffer;

@Entity @Table(name = "order_items")
public class OrderItem {
    @Id private UUID id;
    @Column(nullable = false, updatable = false) private UUID organizationId;
    @Column(nullable = false, updatable = false) private UUID campaignId;
    @Column(nullable = false, updatable = false) private UUID groupingOfferId;
    @Column(nullable = false) private int lineNumber;
    @Column(nullable = false) private UUID productIdSnapshot;
    @Column(nullable = false) private UUID productVariantIdSnapshot;
    @Column(nullable = false, length = 160) private String productNameSnapshot;
    @Column(length = 5000) private String productDescriptionSnapshot;
    @Column(length = 160) private String commercialLabelSnapshot;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private Map<String, String> variantAttributesSnapshot;
    @Column(length = 80) private String skuSnapshot;
    @Column(nullable = false, length = 32) private String saleUnitSnapshot;
    @Column(nullable = false) private int quantity;
    @Column(nullable = false, columnDefinition = "numeric") private BigDecimal unitPriceSnapshot;
    @Column(columnDefinition = "numeric") private BigDecimal estimatedUnitWeightKgSnapshot;
    @Column(columnDefinition = "numeric") private BigDecimal estimatedUnitVolumeM3Snapshot;
    @Column(nullable = false, columnDefinition = "numeric") private BigDecimal lineTotal;
    @Column(nullable = false) private long priceRevisionSnapshot;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false) private Order order;
    protected OrderItem() {}
    static OrderItem snapshot(Order order, int number, CheckoutOffer offer) {
        var item = new OrderItem();
        item.id = UUID.randomUUID(); item.order = order; item.organizationId = order.getOrganizationId();
        item.campaignId = order.getCampaignId(); item.lineNumber = number; item.groupingOfferId = offer.offerId();
        item.productIdSnapshot = offer.productId(); item.productVariantIdSnapshot = offer.productVariantId();
        item.productNameSnapshot = offer.productName(); item.productDescriptionSnapshot = offer.productDescription();
        item.commercialLabelSnapshot = offer.commercialLabel(); item.variantAttributesSnapshot = new TreeMap<>(offer.variantAttributes());
        item.skuSnapshot = offer.sku(); item.saleUnitSnapshot = offer.saleUnit();
        item.quantity = offer.quantity(); item.unitPriceSnapshot = offer.unitPrice();
        item.estimatedUnitWeightKgSnapshot = offer.estimatedUnitWeightKg();
        item.estimatedUnitVolumeM3Snapshot = offer.estimatedUnitVolumeM3();
        item.lineTotal = offer.unitPrice().multiply(BigDecimal.valueOf(offer.quantity())).setScale(0);
        item.priceRevisionSnapshot = offer.priceRevision();
        return item;
    }
    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getCampaignId() { return campaignId; }
    public UUID getGroupingOfferId() { return groupingOfferId; }
    public int getLineNumber() { return lineNumber; }
    public UUID getProductIdSnapshot() { return productIdSnapshot; }
    public UUID getProductVariantIdSnapshot() { return productVariantIdSnapshot; }
    public String getProductNameSnapshot() { return productNameSnapshot; }
    public String getProductDescriptionSnapshot() { return productDescriptionSnapshot; }
    public String getCommercialLabelSnapshot() { return commercialLabelSnapshot; }
    public Map<String, String> getVariantAttributesSnapshot() { return Collections.unmodifiableMap(variantAttributesSnapshot); }
    public String getSkuSnapshot() { return skuSnapshot; }
    public String getSaleUnitSnapshot() { return saleUnitSnapshot; }
    public int getQuantity() { return quantity; }
    public BigDecimal getUnitPriceSnapshot() { return unitPriceSnapshot; }
    public BigDecimal getEstimatedUnitWeightKgSnapshot() { return estimatedUnitWeightKgSnapshot; }
    public BigDecimal getEstimatedUnitVolumeM3Snapshot() { return estimatedUnitVolumeM3Snapshot; }
    public BigDecimal getLineTotal() { return lineTotal; }
    public long getPriceRevisionSnapshot() { return priceRevisionSnapshot; }
}
