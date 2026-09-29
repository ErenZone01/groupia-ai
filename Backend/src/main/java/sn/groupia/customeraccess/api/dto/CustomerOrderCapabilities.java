package sn.groupia.customeraccess.api.dto;
/** Eligibility hint; the mutation always rechecks authorization and archive rules. */
public record CustomerOrderCapabilities(boolean canResendTrackingLink) {}
