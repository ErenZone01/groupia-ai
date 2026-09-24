package sn.groupia.payment.application.service;
import java.util.*;
import org.springframework.stereotype.Component;
import sn.groupia.payment.domain.service.PaymentProvider;
import sn.groupia.payment.domain.service.WebhookSuccessEvidence;
import sn.groupia.shared.api.ApiException;

@Component
class ProviderRegistry {
    private final Map<String,PaymentProvider> providers;
    private record WebhookPolicy(String account,WebhookSuccessEvidence evidence) {}
    private final Map<String,WebhookPolicy> webhookPolicies;
    ProviderRegistry(List<PaymentProvider> adapters) {
        var map = new HashMap<String,PaymentProvider>();
        var policies = new HashMap<String,WebhookPolicy>();
        for (var adapter : adapters) {
            if (!adapter.name().matches("[A-Z0-9_]{1,64}") || (adapter.accountReference()!=null && adapter.accountReference().length()>128)
                    || map.put(adapter.name(),adapter)!=null) { throw new IllegalStateException("Invalid provider registration"); }
            policies.put(adapter.name(),new WebhookPolicy(adapter.accountReference(),Objects.requireNonNull(adapter.webhookSuccessEvidence())));
        }
        providers = Map.copyOf(map);
        webhookPolicies = Map.copyOf(policies);
    }
    boolean acceptsAuthenticatedWebhook(String provider,String account) {
        var policy=webhookPolicies.get(provider);
        return policy!=null && Objects.equals(policy.account(),account)
                && policy.evidence()==WebhookSuccessEvidence.AUTHENTICATED_WEBHOOK;
    }
    PaymentProvider require(String name) {
        var provider = name==null?null:providers.get(name.toUpperCase(Locale.ROOT));
        if (provider == null) { throw new ApiException(503,"PAYMENT_PROVIDER_UNAVAILABLE","Ce fournisseur de paiement est indisponible."); }
        return provider;
    }
}
