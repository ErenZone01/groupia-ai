package sn.groupia.grouping.application.service;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.grouping.api.CampaignCompletion;
import sn.groupia.grouping.domain.model.*;
import sn.groupia.grouping.domain.repository.*;
import sn.groupia.organization.api.*;
import sn.groupia.shared.api.ApiException;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CampaignCompletionService implements CampaignCompletion {
    private final Campaigns campaigns;
    private final GroupingChanges changes;
    private final OrganizationAccess access;
    private final Clock clock;

    public CampaignCompletionService(
            Campaigns campaigns, GroupingChanges changes, OrganizationAccess access, Clock clock) {
        this.campaigns = campaigns;
        this.changes = changes;
        this.access = access;
        this.clock = clock;
    }

    public State read(UUID org, UUID id) {
        access.requirePermission(org, OrganizationPermission.VIEW_GROUPINGS);
        return view(campaigns.find(org, id).orElseThrow(CampaignCompletionService::missing));
    }

    public State lock(UUID org, UUID id, long version) {
        return view(locked(org, id, version));
    }

    public State complete(UUID org, UUID id, long version) {
        var actor = access.requireActorPermission(org, OrganizationPermission.MANAGE_GROUPINGS);
        var c = locked(org, id, version);
        c.complete(clock.instant());
        campaigns.save(c);
        changes.append(GroupingChange.completion(c, actor));
        return view(c);
    }

    private GroupingCampaign locked(UUID org, UUID id, long version) {
        access.requirePermission(org, OrganizationPermission.MANAGE_GROUPINGS);
        var c = campaigns.lock(org, id).orElseThrow(CampaignCompletionService::missing);
        c.requireVersion(version);
        if (c.getStatus() != GroupingCampaignStatus.DISTRIBUTING)
            throw new ApiException(
                    409,
                    "INVALID_GROUPING_TRANSITION",
                    "Seul un groupage en distribution peut être terminé.");
        return c;
    }

    private State view(GroupingCampaign c) {
        return new State(c.getId(), c.getStatus().name(), c.getVersion(), c.getCompletedAt());
    }

    private static ApiException missing() {
        return new ApiException(404, "GROUPING_CAMPAIGN_NOT_FOUND", "Groupage introuvable.");
    }
}
