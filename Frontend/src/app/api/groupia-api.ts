import { inject, Injectable } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';
import {
  CatalogControllerService,
  GroupingControllerService,
  PublicGroupingControllerService,
} from './generated/services';
import {
  AddOffer,
  CampaignVersion,
  CreateCampaign,
  CreateProduct,
  ProductView,
  UpdateCampaign,
  UpdateOffer,
} from './generated/models';
/** Application facade: generated contracts stay unmodified. No business state is inferred here. */
@Injectable({ providedIn: 'root' })
export class GroupiaApi {
  private readonly catalog = inject(CatalogControllerService);
  private readonly grouping = inject(GroupingControllerService);
  private readonly publicApi = inject(PublicGroupingControllerService);
  products(organizationId: string, page = 0, size = 20) {
    return firstValueFrom(this.catalog.list1({ organizationId, page, size }));
  }
  product(organizationId: string, productId: string) {
    return firstValueFrom(this.catalog.get({ organizationId, productId }));
  }
  createProduct(organizationId: string, body: CreateProduct) {
    return firstValueFrom(this.catalog.create1({ organizationId, body }));
  }
  campaigns(organizationId: string, page = 0) {
    return firstValueFrom(this.grouping.list2({ organizationId, page, size: 20 }));
  }
  campaign(organizationId: string, campaignId: string) {
    return firstValueFrom(this.grouping.get1({ organizationId, campaignId }));
  }
  createCampaign(organizationId: string, body: CreateCampaign) {
    return firstValueFrom(this.grouping.create2({ organizationId, body }));
  }
  updateCampaign(organizationId: string, campaignId: string, body: UpdateCampaign) {
    return firstValueFrom(this.grouping.update1({ organizationId, campaignId, body }));
  }
  addOffer(organizationId: string, campaignId: string, body: AddOffer) {
    return firstValueFrom(this.grouping.addOffer({ organizationId, campaignId, body }));
  }
  updateOffer(organizationId: string, campaignId: string, offerId: string, body: UpdateOffer) {
    return firstValueFrom(this.grouping.updateOffer({ organizationId, campaignId, offerId, body }));
  }
  openCampaign(organizationId: string, campaignId: string, body: CampaignVersion) {
    return firstValueFrom(this.grouping.open({ organizationId, campaignId, body }));
  }
  closeCampaign(organizationId: string, campaignId: string, body: CampaignVersion) {
    return firstValueFrom(this.grouping.close({ organizationId, campaignId, body }));
  }
  publicGrouping(publicIdentifier: string) {
    return firstValueFrom(this.publicApi.get2({ publicIdentifier }).pipe(timeout(15000)));
  }
  async allProducts(organizationId: string) {
    const result: ProductView[] = [];
    for (let page = 0; ; page++) {
      const list = await this.products(organizationId, page, 100);
      const items = list.items ?? [];
      // The API has no variant lookup endpoint; keep detail requests bounded.
      for (let i = 0; i < items.length; i += 6)
        result.push(
          ...(await Promise.all(
            items.slice(i, i + 6).map((p) => this.product(organizationId, p.id!)),
          )),
        );
      if (page + 1 >= (list.totalPages ?? 1)) break;
    }
    return result;
  }
}
