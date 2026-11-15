package sn.groupia.subscription.api.dto;

import java.time.Instant;

/** Deterministic in-app reminder; never represents consent to an automatic debit. */
public record SubscriptionRenewalView(Instant dueAt, String reminder, boolean manualRenewal) {}
