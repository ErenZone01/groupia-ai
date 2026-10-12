package sn.groupia.ordering.api.dto;

import java.time.Instant;
import java.util.*;

public record GroupingCompletionView(
        boolean eligible,
        boolean canComplete,
        String campaignStatus,
        long campaignVersion,
        Instant completedAt,
        int totalOrders,
        long completedOrders,
        long terminalUnexecutedOrders,
        Map<String, Long> counts,
        List<Blocker> blockers) {
    public record Blocker(String code, UUID orderId, String orderNumber) {}
}
