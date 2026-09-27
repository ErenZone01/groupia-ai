package sn.groupia.grouping.api.dto;
import sn.groupia.shipping.api.dto.ShipmentView;
public record CampaignShipmentView(CampaignLifecycleView campaign, ShipmentView shipment) {}
