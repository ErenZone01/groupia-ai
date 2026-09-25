package sn.groupia.payment.application.service;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.groupia.payment.api.*;
import sn.groupia.payment.api.dto.*;
import sn.groupia.payment.domain.repository.FinancialStore;
import sn.groupia.organization.api.*;
import sn.groupia.shared.api.ApiException;
@Service @Transactional(readOnly=true)
public class FinancialQueries {
    private final FinancialStore store;
    private final OrganizationAccess access;
    FinancialQueries(FinancialStore store,OrganizationAccess access) { this.store=store; this.access=access; }
    public record Page(List<PaymentRequestView> items,int page,int size,long totalElements,long totalPages) {}
    public record Attempt(UUID id,String provider,String providerTransactionId,PaymentStatus status,BigDecimal expectedAmount,
            String currency,BigDecimal confirmedAmount,String confirmedCurrency,boolean reconciliationRequired,
            String reconciliationReason,Instant initiatedAt,Instant confirmedAt,Instant lastCheckedAt) {}
    public record Detail(PaymentRequestView request,List<Attempt> attempts) {}
    public Page list(UUID org,int page,int size) {
        access.requirePermission(org,OrganizationPermission.VIEW_PAYMENTS);
        if (page<0 || page>1000000 || size<1 || size>100) { throw new ApiException(400,"INVALID_REQUEST","Pagination invalide."); }
        var result=store.list(org,page,size);
        return new Page(result.items().stream().map(r->r.view()).toList(),page,size,result.total(),(result.total()+size-1)/size);
    }
    public Detail get(UUID org,UUID id) {
        access.requirePermission(org,OrganizationPermission.VIEW_PAYMENTS);
        var result=store.request(id).filter(r->r.getOrganizationId().equals(org))
                .orElseThrow(()->new ApiException(404,"PAYMENT_REQUEST_NOT_FOUND","Obligation introuvable."));
        return new Detail(result.view(),store.attempts(id).stream().map(p->new Attempt(p.getId(),p.getProvider(),p.getProviderTransactionId(),
                p.getStatus(),p.getExpectedAmount(),p.getCurrency(),p.getConfirmedAmount(),p.getConfirmedCurrency(),
                p.isReconciliationRequired(),p.getReconciliationReason(),p.getInitiatedAt(),p.getConfirmedAt(),p.getLastCheckedAt())).toList());
    }
}
