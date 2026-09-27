package sn.groupia.grouping.api.dto;
import java.time.Instant;
/** Safe allowlist for an order holder. No provider reference, note, actor or internal identifiers. */
public record GroupingProgress(String status, Instant departedAt, Instant expectedArrivalAt, Instant arrivedAt) {}
