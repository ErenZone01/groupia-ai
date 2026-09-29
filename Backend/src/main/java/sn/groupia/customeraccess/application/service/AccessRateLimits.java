package sn.groupia.customeraccess.application.service;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.customeraccess.domain.repository.AccessStore;
import sn.groupia.shared.api.ApiException;
@Service
public class AccessRateLimits {
    private final AccessStore store;private final AccessCrypto crypto;private final Clock clock;
    AccessRateLimits(AccessStore store,AccessCrypto crypto,Clock clock){this.store=store;this.crypto=crypto;this.clock=clock;}
    @Transactional(propagation=Propagation.REQUIRES_NEW,noRollbackFor=ApiException.class)
    public void consume(String subject,int seconds,int max) {
        if(store.limit(crypto.subject(subject),clock.instant(),seconds)>max)throw new ApiException(429,"CUSTOMER_ACCESS_RATE_LIMITED","Patientez avant de réessayer.");
    }
}
