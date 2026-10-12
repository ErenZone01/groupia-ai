package sn.groupia.grouping.api;

import java.time.Instant;
import java.util.UUID;

public interface CampaignCompletion {
    record State(UUID id, String status, long version, Instant completedAt) {}

    State read(UUID org, UUID campaign);

    State lock(UUID org, UUID campaign, long version);

    State complete(UUID org, UUID campaign, long version);
}
