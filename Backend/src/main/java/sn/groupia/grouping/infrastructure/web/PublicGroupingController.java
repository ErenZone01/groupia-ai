package sn.groupia.grouping.infrastructure.web;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import sn.groupia.grouping.api.GroupingQueries;
import sn.groupia.grouping.api.dto.PublicGroupingView;

@RestController
@RequestMapping("/api/public/groupings")
class PublicGroupingController {
    private final GroupingQueries grouping;
    PublicGroupingController(GroupingQueries grouping) { this.grouping = grouping; }
    @GetMapping("/{publicIdentifier}")
    ResponseEntity<PublicGroupingView> get(@PathVariable String publicIdentifier) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(grouping.getPublicGrouping(publicIdentifier));
    }
}
