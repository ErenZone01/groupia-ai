package sn.groupia.catalog.api.dto;
import java.util.List;
public record ProductPage(List<ProductSummary> items, int page, int size, long totalElements, int totalPages) {}
