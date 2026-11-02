import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export interface DirectOrderSummary {
  id: string;
  orderNumber: string;
  status: string;
  fulfillmentMethod: 'PICKUP' | 'DELIVERY';
  currency: 'XOF';
  goodsSubtotalXof: number;
  platformFeeXof: number;
  amountBeforeShippingXof: number;
  shippingFeeXof: number | null;
  acceptedTotalXof: number | null;
  shippingRateVersion: number | null;
  shippingQuoted: boolean;
  placedAt: string;
  itemCount: number;
}

export interface DirectOrdersPage {
  items: DirectOrderSummary[];
  total: number;
  page: number;
  size: number;
}

export interface DirectOrderDetail {
  order: DirectOrderSummary;
  contact: {
    firstName: string;
    lastName: string;
    phoneE164: string;
    email: string | null;
  };
  deliveryAddress: {
    country: string;
    region: string;
    city: string;
    commune: string;
    neighborhood: string | null;
    details: string;
    locationMethod: 'MAP' | 'MANUAL' | null;
    coverageStatus: 'REGION_MATCHED' | 'UNVERIFIED_MANUAL' | null;
  } | null;
  platformFeeRule: Record<string, string>;
  items: {
    lineNumber: number;
    productName: string;
    attributes: Record<string, string>;
    quantity: number;
    unitPriceXof: number;
    lineTotalXof: number;
    priceRevision: number;
  }[];
}

/** Authenticated merchant reads. No public endpoint or payment action. */
@Injectable({ providedIn: 'root' })
export class DirectOrdersApi {
  private readonly http = inject(HttpClient);

  private path(organizationId: string) {
    return '/api/organizations/' + encodeURIComponent(organizationId) + '/direct-orders';
  }

  list(organizationId: string, page: number, size = 20) {
    const params = new HttpParams().set('page', page).set('size', size);
    return firstValueFrom(this.http.get<DirectOrdersPage>(
      this.path(organizationId), { params },
    ).pipe(timeout(15000)));
  }

  get(organizationId: string, orderId: string) {
    return firstValueFrom(this.http.get<DirectOrderDetail>(
      this.path(organizationId) + '/' + encodeURIComponent(orderId),
    ).pipe(timeout(15000)));
  }
}
