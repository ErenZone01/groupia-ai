package sn.groupia.payment.api.dto;

import sn.groupia.payment.api.PaymentOperationalStatus;

public record PaymentRequestState(
        PaymentRequestView request,
        PaymentOperationalStatus operationalStatus,
        boolean attemptInProgress,
        boolean reconciliationRequired) {}
