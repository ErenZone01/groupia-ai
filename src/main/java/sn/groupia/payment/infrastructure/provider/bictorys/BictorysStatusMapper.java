package sn.groupia.payment.infrastructure.provider.bictorys;
import java.util.Locale;
import sn.groupia.payment.api.PaymentStatus;
import sn.groupia.payment.domain.service.ProviderMetadata.ReviewReason;
final class BictorysStatusMapper {
    record State(PaymentStatus status,ReviewReason review) {}
    static State map(String raw) {
        return switch(raw==null?"":raw.toLowerCase(Locale.ROOT)) {
            case "succeeded","success","réussi" -> new State(PaymentStatus.SUCCEEDED,null);
            case "pending","processing" -> new State(PaymentStatus.PENDING,null);
            case "failed" -> new State(PaymentStatus.FAILED,null);
            case "cancelled" -> new State(PaymentStatus.CANCELLED,null);
            // Pre-capture authorization is not proof of a debit; no capture flow in this slice.
            case "authorized" -> new State(PaymentStatus.PENDING,ReviewReason.AUTHORIZATION_NOT_CAPTURED);
            case "reversed" -> new State(PaymentStatus.CREATED,ReviewReason.PROVIDER_REVERSAL);
            default -> new State(PaymentStatus.CREATED,ReviewReason.PROVIDER_STATUS_UNKNOWN);
        };
    }
}
