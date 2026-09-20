package sn.groupia.ordering.application.service;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.grouping.api.CheckoutAdmission;
import sn.groupia.ordering.application.command.CheckoutCommand;
import sn.groupia.ordering.api.dto.*;
import sn.groupia.ordering.domain.model.*;
import sn.groupia.ordering.domain.repository.Orders;
import sn.groupia.shipping.api.ShippingEstimator;
import sn.groupia.shipping.api.dto.ShippingEstimateLine;
import sn.groupia.shared.api.ApiException;

@Service
public class CheckoutService {
    private final CheckoutAdmission grouping;
    private final ShippingEstimator shipping;
    private final Orders orders;
    private final CheckoutInput input;
    private final CheckoutSecrets secrets;
    private final CheckoutPolicy policy;
    private final Clock clock;
    CheckoutService(CheckoutAdmission grouping, ShippingEstimator shipping, Orders orders, CheckoutInput input,
            CheckoutSecrets secrets, CheckoutPolicy policy, Clock clock) {
        this.grouping = grouping; this.shipping = shipping; this.orders = orders; this.input = input;
        this.secrets = secrets; this.policy = policy; this.clock = clock;
    }
    public record Result(boolean created, Object body) {}
    @Transactional(timeout = 15)
    public Result checkout(UUID publicId, String rawKey, CheckoutCommand command) {
        String key = input.key(rawKey);
        var lines = input.lines(command);
        var context = grouping.lockCampaign(publicId);
        var contact = input.contact(command.contact(), context.countryCode());
        String fingerprint = secrets.fingerprint(publicId, contact, lines);
        orders.lockIdempotency(context.organizationId(), key);
        var previous = orders.findByKey(context.organizationId(), key);
        if (previous.isPresent()) {
            if (!previous.get().getCheckoutRequestFingerprint().equals(fingerprint)) {
                throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED", "Cette clé a déjà été utilisée avec un autre contenu.");
            }
            return new Result(false, new OrderReplay(previous.get().getOrderNumber(), true));
        }
        var quote = grouping.quote(context, lines);
        var estimate = shipping.estimate(quote.shippingTerms(), quote.offers().stream()
                .map(o -> new ShippingEstimateLine(o.quantity(), o.estimatedUnitWeightKg(), o.estimatedUnitVolumeM3())).toList());
        var now = clock.instant();
        var customer = Customer.create(context.organizationId(), contact, now);
        String token = secrets.token();
        var order = Order.place(new Order.Placement(quote, customer, contact, orders.nextOrderNumber(),
                policy.fee(), policy.feeRule(), estimate, key, fingerprint, secrets.hash(token), now,
                now.plus(policy.paymentWindow()), now.plus(policy.trackingLifetime())));
        orders.add(customer, order);
        // The transaction interceptor commits before this result reaches the HTTP controller.
        return new Result(true, OrderViews.receipt(order, token));
    }
}
