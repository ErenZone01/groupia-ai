import { inject, Injectable } from '@angular/core';
import { timeout } from 'rxjs';
import { OrderingControllerService } from '../../api/generated/services';
@Injectable({ providedIn: 'root' })
export class OrdersApi {
  private readonly api = inject(OrderingControllerService);
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
