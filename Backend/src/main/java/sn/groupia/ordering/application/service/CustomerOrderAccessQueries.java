package sn.groupia.ordering.application.service;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.ordering.api.CustomerOrderAccess;
import sn.groupia.ordering.api.dto.*;
import sn.groupia.ordering.application.query.CustomerAccessReadModel;
import sn.groupia.ordering.domain.repository.Orders;
import sn.groupia.grouping.api.GroupingProgressQueries;
import sn.groupia.shared.api.ApiException;
@Service @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class CustomerOrderAccessQueries implements CustomerOrderAccess {
    private final CustomerAccessReadModel read;private final Orders orders;private final GroupingProgressQueries groups;
    CustomerOrderAccessQueries(CustomerAccessReadModel read,Orders orders,GroupingProgressQueries groups){this.read=read;this.orders=orders;this.groups=groups;}
    public CustomerOrderIdentity identity(UUID org,UUID id){return read.identity(org,id);}
    public CustomerOrderIdentity forPhone(String phone,String number){return read.forPhone(phone,number);}
    public MyOrdersPage findOrdersForVerifiedPhone(String phone,int page,int size) {
        if(page<0 || page>100000 || size<1 || size>50)throw new ApiException(400,"INVALID_REQUEST","Pagination invalide.");
        var rows=read.list(phone,Math.multiplyExact(page,size),size);
        var details=groups.progressForOrders(rows.stream().map(CustomerAccessReadModel.Row::campaign).collect(Collectors.toSet()));
        long count=read.count(phone);
        return new MyOrdersPage(rows.stream().map(r->new MyOrderSummary(r.number(),r.placedAt(),r.status(),r.fulfillment(),
            r.amount(),r.currency(),details.get(r.campaign()))).toList(),page,size,count,(int)((count+size-1)/size));
    }
    public OrderTrackingView track(UUID org,UUID id) {
        var o=orders.find(org,id).orElseThrow(()->new ApiException(404,"CUSTOMER_ORDER_NOT_FOUND","Commande inaccessible."));
        return OrderTrackingView.from(OrderViews.receipt(o,null),groups.progress(org,o.getCampaignId()));
    }
}
