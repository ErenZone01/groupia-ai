package sn.groupia.payment.domain.service;
/** Provider-neutral operational details; a charge identifier is not a transaction identifier. */
public record ProviderMetadata(String paymentReference,String checkoutUrl,String chargeId,ReviewReason reviewReason) {
    public enum ReviewReason { PROVIDER_REVERSAL, AUTHORIZATION_NOT_CAPTURED, PROVIDER_STATUS_UNKNOWN }
    @Override public String toString(){return "ProviderMetadata[redacted]";}
}
