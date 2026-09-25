package sn.groupia.ordering.application.service;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.ordering.domain.repository.Orders;
@Service
public class OrderExpiration {
    private final Orders orders; private final OrderExpirationTransaction worker; private final Clock clock; private final int batchSize;
    OrderExpiration(Orders orders,OrderExpirationTransaction worker,Clock clock,@Value("${groupia.ordering.expiration.batch-size:100}") int batchSize) {
        if(batchSize<1 || batchSize>1000){throw new IllegalArgumentException("Invalid expiration batch size");}
        this.orders=orders; this.worker=worker; this.clock=clock; this.batchSize=batchSize;
    }
    @Transactional(propagation=Propagation.NEVER)
    public int expireDue() {
        int count=0;
        var cutoff=clock.instant();
        java.util.UUID after=null;
        while(true) {
            var page=orders.expiredCandidates(cutoff,after,batchSize);
            if(page.isEmpty()){break;}
            for(var id:page){if(worker.expire(id)){count++;}}
            after=page.getLast();
        }
        return count;
    }
}
