package sn.groupia.payment.application.service;
import java.util.*;
import org.springframework.stereotype.Component;
import sn.groupia.payment.domain.service.PaymentProvider;
import sn.groupia.shared.api.ApiException;

@Component
class ProviderRegistry {
    private final Map<String,PaymentProvider> providers;
    ProviderRegistry(List<PaymentProvider> adapters) {
        var map = new HashMap<String,PaymentProvider>();
        for (var adapter : adapters) {
            if (!adapter.name().matches("[A-Z0-9_]{1,64}") || (adapter.accountReference()!=null && adapter.accountReference().length()>128)
                    || map.put(adapter.name(),adapter)!=null) { throw new IllegalStateException("Invalid provider registration"); }
        }
        providers = Map.copyOf(map);
    }
    PaymentProvider require(String name) {
        var provider = name==null?null:providers.get(name.toUpperCase(Locale.ROOT));
        if (provider == null) { throw new ApiException(503,"PAYMENT_PROVIDER_UNAVAILABLE","Ce fournisseur de paiement est indisponible."); }
        return provider;
    }
}
