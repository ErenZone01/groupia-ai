import { inject, Injectable } from '@angular/core';
import { timeout } from 'rxjs';
import {
  OrderingControllerService,
  SupplierSummaryControllerService,
  SupplierOrderControllerService,
} from '../../api/generated/services';
@Injectable({ providedIn: 'root' })
export class OrdersApi {
  private readonly api = inject(OrderingControllerService);
  private readonly summaries = inject(SupplierSummaryControllerService);
  private readonly purchases = inject(SupplierOrderControllerService);
  supplierOrder(organizationId: string, campaignId: string) {
    return this.purchases.readSupplierOrder({ organizationId, campaignId }).pipe(timeout(15000));
  }
  createSupplierOrder(organizationId: string, campaignId: string, campaignVersion: number) {
    return this.purchases
      .createSupplierOrder({ organizationId, campaignId, body: { campaignVersion } })
      .pipe(timeout(15000));
  }
  supplierSummary(organizationId: string, campaignId: string) {
    return this.summaries.supplierSummary({ organizationId, campaignId }).pipe(timeout(15000));
  }
  list(
    organizationId: string,
    options: { campaignId?: string; status?: string; page?: number; size?: number } = {},
  ) {
    return this.api.list4({ organizationId, page: 0, size: 20, ...options }).pipe(timeout(15000));
  }
  detail(organizationId: string, orderId: string) {
    return this.api.get5({ organizationId, orderId }).pipe(timeout(15000));
  }
}
