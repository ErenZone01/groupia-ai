package sn.groupia.ordering.application.service;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** New production orders never charge a Groupia commission; historical snapshots stay intact. */
@Component
@Profile("prod")
public class ProductionCommercePolicy {
    public ProductionCommercePolicy(@Value("${groupia.ordering.platform-fee-xof}") BigDecimal fee) {
        if (fee == null || fee.signum() != 0)
            throw new IllegalStateException("Groupia production order commission must be zero");
    }
}
