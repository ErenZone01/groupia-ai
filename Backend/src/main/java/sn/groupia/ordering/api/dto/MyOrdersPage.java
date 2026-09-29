package sn.groupia.ordering.api.dto;
import java.util.List;
public record MyOrdersPage(List<MyOrderSummary> items,int page,int size,long totalElements,int totalPages) {}
