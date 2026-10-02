import { inject, Injectable } from '@angular/core';
import { timeout } from 'rxjs';
import { CampaignLogisticsControllerService } from '../../api/generated/services';
import {
  ArriveCampaign,
  ShipCampaign,
  UpdateShipment,
  UpdateShipmentEta,
  AddShipmentFact,
} from '../../api/generated/models';
@Injectable({ providedIn: 'root' })
export class LogisticsApi {
  private readonly api = inject(CampaignLogisticsControllerService);
  read(organizationId: string, campaignId: string) {
    return this.api.readCampaignShipment({ organizationId, campaignId }).pipe(timeout(15000));
  }
  ship(organizationId: string, campaignId: string, body: ShipCampaign) {
    return this.api.shipCampaign({ organizationId, campaignId, body }).pipe(timeout(15000));
  }
  enrich(organizationId: string, campaignId: string, body: UpdateShipment) {
    return this.api
      .updateCampaignShipment({ organizationId, campaignId, body })
      .pipe(timeout(15000));
  }
  estimate(organizationId: string, campaignId: string, body: UpdateShipmentEta) {
    return this.api
      .updateCampaignShipmentEta({ organizationId, campaignId, body })
      .pipe(timeout(15000));
  }
  addFact(organizationId: string, campaignId: string, body: AddShipmentFact) {
    return this.api
      .addCampaignShipmentFact({ organizationId, campaignId, body })
      .pipe(timeout(15000));
  }
  arrive(organizationId: string, campaignId: string, body: ArriveCampaign) {
    return this.api.arriveCampaign({ organizationId, campaignId, body }).pipe(timeout(15000));
  }
}
