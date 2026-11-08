package sn.groupia.payment.api;

import java.math.BigDecimal;
import java.util.*;
import sn.groupia.payment.api.dto.*;

/** Internal projection API. Caller must authorize the organization before exposing these facts. */
public interface CollectionFacts {
    record Fact(
            PaymentSettlementEvidence evidence,
            BigDecimal sellerPrincipal,
            BigDecimal platformFee,
            BigDecimal collectionFee,
            BigDecimal customerCharged) {}

    Fact read(UUID organizationId, UUID requestId);

    List<UUID> settledRequests(UUID organizationId, UUID after, int limit);
}
