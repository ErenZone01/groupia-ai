package sn.groupia.grouping.api;
import sn.groupia.grouping.api.dto.PublicGroupingView;
public interface GroupingQueries {
    PublicGroupingView getPublicGrouping(String publicIdentifier);
}
