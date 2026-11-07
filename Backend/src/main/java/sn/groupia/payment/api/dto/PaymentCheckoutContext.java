package sn.groupia.payment.api.dto;

/**
 * Server-owned checkout snapshots, supplied only for initiation; never sourced from public payment
 * input.
 */
public record PaymentCheckoutContext(
        String businessReference,
        String customerName,
        String phone,
        String email,
        java.util.UUID feeQuoteId) {
    public PaymentCheckoutContext(
            String businessReference, String customerName, String phone, String email) {
        this(businessReference, customerName, phone, email, null);
    }

    public PaymentCheckoutContext withFeeQuote(java.util.UUID id) {
        return new PaymentCheckoutContext(businessReference, customerName, phone, email, id);
    }

    /** SasPay requires a Senegalese phone, email and both customer names. */
    public boolean saspayEligible() {
        return customerName != null
                && customerName.strip().split("\\s+", 2).length == 2
                && phone != null
                && phone.matches("\\+221[0-9]{9}")
                && email != null
                && email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    }

    @Override
    public String toString() {
        return "PaymentCheckoutContext[redacted]";
    }
}
