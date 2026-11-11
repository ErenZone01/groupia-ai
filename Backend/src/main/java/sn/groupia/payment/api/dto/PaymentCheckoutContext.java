package sn.groupia.payment.api.dto;

/**
 * Server-owned order facts, not public prices or merchant accounts. A direct customer may
 * supplement a missing checkout email after authenticating the private order capability; the
 * immutable original order snapshot is preserved.
 */
public record PaymentCheckoutContext(
        String businessReference,
        String customerName,
        String phone,
        String email,
        java.util.UUID feeQuoteId,
        String requestedPayerEmail) {

    public PaymentCheckoutContext(
            String businessReference, String customerName, String phone, String email) {
        this(businessReference, customerName, phone, email, null, null);
    }

    public PaymentCheckoutContext(
            String businessReference,
            String customerName,
            String phone,
            String email,
            java.util.UUID feeQuoteId) {
        this(businessReference, customerName, phone, email, feeQuoteId, null);
    }

    public PaymentCheckoutContext withFeeQuote(java.util.UUID id) {
        return new PaymentCheckoutContext(
                businessReference, customerName, phone, email, id, requestedPayerEmail);
    }

    public PaymentCheckoutContext withPayerEmail(String candidate) {
        return new PaymentCheckoutContext(
                businessReference, customerName, phone, email, feeQuoteId, candidate);
    }

    public PaymentCheckoutContext withAuthorizedPayerEmail(String approvedEmail) {
        return new PaymentCheckoutContext(
                businessReference, customerName, phone, approvedEmail, feeQuoteId, null);
    }

    /** Legacy softpay validation (not required for hosted payment network selection). */
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
