package sn.groupia.payment.api.dto;
/** Internal module contract. Blocking includes uncertain attempts even on an allocated request. */
public record PaymentObligationView(PaymentRequestView request, boolean paymentInProgress) {}
