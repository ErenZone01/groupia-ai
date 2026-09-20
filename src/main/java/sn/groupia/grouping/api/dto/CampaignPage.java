package sn.groupia.grouping.api.dto;
import java.util.List;
public record CampaignPage(List<CampaignSummary> items, int page, int size, long totalElements, int totalPages) {}
