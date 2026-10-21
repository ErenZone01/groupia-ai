package sn.groupia.ordering.infrastructure.web;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sn.groupia.ordering.api.dto.BusinessAnalyticsView;
import sn.groupia.ordering.application.service.BusinessAnalyticsService;

@RestController
class BusinessAnalyticsController {
    private final BusinessAnalyticsService analytics;

    BusinessAnalyticsController(BusinessAnalyticsService analytics) {
        this.analytics = analytics;
    }

    @io.swagger.v3.oas.annotations.Operation(operationId = "businessAnalytics")
    @GetMapping("/api/organizations/{organizationId}/analytics/business")
    ResponseEntity<BusinessAnalyticsView> overview(
            @PathVariable UUID organizationId, @RequestParam(defaultValue = "12") int months) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(analytics.overview(organizationId, months));
    }
}
