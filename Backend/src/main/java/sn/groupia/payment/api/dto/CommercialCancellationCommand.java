package sn.groupia.payment.api.dto;

public record CommercialCancellationCommand(Long version, String reason, boolean confirmed) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void unknown(String key, Object value) {
        throw new IllegalArgumentException("Unsupported cancellation field");
    }
}
