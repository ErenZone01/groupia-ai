package sn.groupia.payment.api.dto;

/**
 * Server-owned checkout snapshots, supplied only for initiation; never sourced from public payment
 * input.
 */
public record PaymentCheckoutContext(
        String businessReference, String customerName, String phone, String email) {
    @Override
    public String toString() {
        return "PaymentCheckoutContext[redacted]";
    }
}
