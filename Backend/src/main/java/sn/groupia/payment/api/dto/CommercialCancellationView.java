package sn.groupia.payment.api.dto;

import java.util.UUID;

public record CommercialCancellationView(
        UUID caseId,
        String status,
        String orderStatus,
        long orderVersion,
        boolean canCancel,
        String blocker) {}
