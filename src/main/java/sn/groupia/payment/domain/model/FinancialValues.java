package sn.groupia.payment.domain.model;
import java.math.*;
import sn.groupia.shared.api.ApiException;
final class FinancialValues {
    private FinancialValues() {}
    static BigDecimal money(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > 0
                || value.compareTo(new BigDecimal("1000000000000000000")) >= 0) {
            throw new ApiException(400,"INVALID_PAYMENT_AMOUNT","Montant XOF invalide.");
        }
        return value.setScale(0, RoundingMode.UNNECESSARY);
    }
}
