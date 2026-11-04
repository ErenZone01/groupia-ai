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
    region: string | null;
    city: string | null;
    commune: string | null;
    neighborhood: string | null;
    details: string | null;
    locationMethod: 'MAP' | 'MANUAL' | null;
    coverageStatus: 'REGION_MATCHED' | 'UNVERIFIED_MANUAL' | null;
    latitude: number | null;
    longitude: number | null;
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


export type DirectFinancialState =
  | 'NOT_PREPARED' | 'PREPARED' | 'EXPIRED' | 'UNAVAILABLE'
  | 'PAYMENT_IN_PROGRESS' | 'SETTLEMENT_PROCESSING' | 'SETTLEMENT_VERIFIED' | 'REVIEW_REQUIRED';

export interface DirectOrderPaymentStatus {
  orderNumber: string;
  acceptedTotalXof: number | null;
  financialStatus: DirectFinancialState;
  /** This read model is not an authorization to hand over goods. */
  fulfillmentAllowed: boolean;
}

export interface DirectOrderActionReceipt {
  orderNumber: string;
  status: string;
  fulfillmentMethod?: 'PICKUP' | 'DELIVERY';
  cancelledAt?: string;
  preparingAt?: string;
  readyAt?: string;
}

/** Authenticated merchant reads and server-guarded fulfillment actions. */
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
  paymentStatus(organizationId: string, orderId: string) {
    return firstValueFrom(this.http.get<DirectOrderPaymentStatus>(
      this.path(organizationId) + '/' + encodeURIComponent(orderId) + '/payment-status',
    ).pipe(timeout(15000)));
  }

  /** No financial data or target state is accepted from the browser. */
  cancel(organizationId: string, orderId: string) {
    return this.command(organizationId, orderId, 'cancel');
  }

  startPreparation(organizationId: string, orderId: string) {
    return this.command(organizationId, orderId, 'start-preparation');
  }

  markReady(organizationId: string, orderId: string) {
    return this.command(organizationId, orderId, 'mark-ready');
  }

  private command(organizationId: string, orderId: string, action: string) {
    return firstValueFrom(this.http.post<DirectOrderActionReceipt>(
      this.path(organizationId) + '/' + encodeURIComponent(orderId) + '/' + action, {},
    ).pipe(timeout(15000)));
  }
}
