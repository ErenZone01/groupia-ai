package sn.groupia.grouping.api.dto;
import java.math.BigDecimal;
import java.util.UUID;
import sn.groupia.catalog.api.dto.CatalogVariantView;
public record PublicOfferView(UUID offerId, UUID productVariantId, String commercialLabel,
        BigDecimal unitPrice, String currency, boolean active, long version, long priceRevision,
        CatalogVariantView catalog) {}
