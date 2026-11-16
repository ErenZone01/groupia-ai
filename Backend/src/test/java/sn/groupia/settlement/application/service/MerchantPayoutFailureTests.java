package sn.groupia.settlement.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import sn.groupia.organization.api.OrganizationAccess;
import sn.groupia.settlement.domain.model.MerchantPayout;
import sn.groupia.settlement.domain.repository.MerchantFinanceStore;
import sn.groupia.settlement.domain.service.MerchantPayoutProvider;
import sn.groupia.settlement.domain.service.PayoutProviderFailure;
import sn.groupia.settlement.domain.service.PayoutProviderFailure.*;

class MerchantPayoutFailureTests {
    private final MerchantFinanceStore store = mock(MerchantFinanceStore.class);
    private final MerchantPayoutTransactions transactions = mock(MerchantPayoutTransactions.class);
    private final MerchantPayoutProvider provider = mock(MerchantPayoutProvider.class);
    private final MerchantSettlementService service =
            new MerchantSettlementService(
                    transactions,
                    store,
                    mock(MerchantBalances.class),
                    provider,
                    mock(OrganizationAccess.class));

    private MerchantPayout payout() {
        var p = mock(MerchantPayout.class);
        when(p.id()).thenReturn(UUID.randomUUID());
        when(p.organizationId()).thenReturn(UUID.randomUUID());
        when(p.provider()).thenReturn("SASPAY");
        when(p.providerAccountReference()).thenReturn("fixture");
        when(p.method()).thenReturn("WAVE");
        when(p.providerTransactionId()).thenReturn(UUID.randomUUID().toString());
        when(transactions.get(p.organizationId(), p.id())).thenReturn(p);
        when(provider.name()).thenReturn("SASPAY");
        when(provider.account()).thenReturn("fixture");
        when(transactions.available()).thenReturn(true);
        when(transactions.verificationAvailable()).thenReturn(true);
        return p;
    }

    @Test
    void structuredFailurePersistsAReasonAndNeverLeaksArbitraryExceptionData() {
        var p = payout();
        UUID campaign = UUID.randomUUID(), key = UUID.randomUUID(), dest = UUID.randomUUID();
        when(transactions.reserve(p.organizationId(), campaign, key, dest, true, null))
                .thenReturn(p);
        when(transactions.claim(p.organizationId(), p.id())).thenReturn(true);
        var failure =
                new PayoutProviderFailure(
                        Stage.WALLETS,
                        403,
                        Category.AUTHORIZATION,
                        Reason.HTTP_REJECTED,
                        ProviderCode.payout_not_enabled);
        when(provider.initiate(p)).thenThrow(failure);
        var logger = (Logger) LoggerFactory.getLogger(MerchantSettlementService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.request(p.organizationId(), campaign, key, dest, true);
            verify(transactions).initiationFailure(p.organizationId(), p.id(), failure);
            String log = appender.list.getFirst().getFormattedMessage();
            assertThat(log)
                    .contains(
                            "stage=WALLETS",
                            "method=GET",
                            "endpoint=MERCHANT_BALANCES",
                            "httpStatus=403",
                            "providerCode=payout_not_enabled",
                            "submission=NOT_ATTEMPTED");
            assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
            doThrow(new IllegalStateException("SASPAY_PAYOUT_SECRET_221771234567"))
                    .when(provider)
                    .initiate(p);
            service.request(p.organizationId(), campaign, key, dest, true);
            assertThat(appender.list.getLast().getFormattedMessage())
                    .contains("UNCLASSIFIED")
                    .doesNotContain("SECRET", "221771234567");
            verify(transactions).uncertain(p.organizationId(), p.id());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void backgroundReconciliationCanOnlyVerifyExistingReferences() {
        var p = payout();
        when(store.dueForVerification("SASPAY", "fixture"))
                .thenReturn(
                        List.of(new MerchantFinanceStore.VerificationWork(p, UUID.randomUUID())));
        var proof = mock(MerchantPayoutProvider.Proof.class);
        when(provider.verify(p)).thenReturn(proof);
        service.reconcilePending();
        verify(provider).verify(p);
        verify(transactions).verified(p.organizationId(), p.id(), proof);
        verify(provider, never()).initiate(any());
        verify(transactions, never()).claim(any(), any());
        when(transactions.available()).thenReturn(false);
        service.reconcilePending();
        verify(provider, times(2)).verify(p);
        verify(provider, never()).initiate(any());
        when(transactions.verificationAvailable()).thenReturn(false);
        service.reconcilePending();
        verify(store, times(2)).dueForVerification(any(), any());
    }

    @Test
    void failedGetDoesNotConsumeNotificationForPreviouslyFailedPayout() {
        var p = payout();
        when(p.status()).thenReturn("FAILED");
        when(store.dueForVerification("SASPAY", "fixture"))
                .thenReturn(
                        List.of(new MerchantFinanceStore.VerificationWork(p, UUID.randomUUID())));
        when(provider.verify(p))
                .thenThrow(
                        new PayoutProviderFailure(
                                Stage.VERIFY,
                                503,
                                Category.SERVER_ERROR,
                                Reason.HTTP_SERVER_ERROR,
                                ProviderCode.NONE));
        service.reconcilePending();
        verify(store, never()).finishVerification(any(), any());
        verify(provider, never()).initiate(any());
        verify(transactions).uncertain(p.organizationId(), p.id());
    }
}
