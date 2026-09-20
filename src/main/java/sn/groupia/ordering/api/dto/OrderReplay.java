package sn.groupia.ordering.api.dto;
/** Acknowledgement only: idempotency is not a capability to read private order data. */
public record OrderReplay(String orderNumber, boolean replayed) {}
