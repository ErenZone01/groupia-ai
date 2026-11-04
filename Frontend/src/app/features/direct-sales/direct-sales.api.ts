import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export type DirectDeliveryMode = 'ALL' | 'SELECTED' | 'DISABLED';

export interface DirectDeliveryPolicy {
  mode: DirectDeliveryMode;
  allowedRegions: string[];
  version: number;
}

export interface DirectShippingSettings {
  regionFeesXof: Record<string, number>;
  version: number;
}

export interface DirectShippingPreview {
  status: string;
  currency: 'XOF';
  region: string | null;
  locationVerification: string;
  goodsSubtotalXof: number;
  platformFeeXof: number;
  shippingFeeXof: number | null;
  totalXof: number | null;
  shippingQuoted: boolean;
  paymentAvailable: boolean;
}

export interface DirectAddressSuggestion {
  status: 'FOUND' | 'UNAVAILABLE' | 'NO_RESULT';
  countryCode: string | null;
  region: string | null;
  city: string | null;
  neighborhood: string | null;
  formatted: string | null;
  provider: string | null;
}

export interface DirectDeliveryCoverage {
  status: 'INSIDE_ZONE' | 'OUTSIDE_ZONE' | 'OUTSIDE_SENEGAL' | 'UNCERTAIN' | 'DELIVERY_DISABLED';
  region: string | null;
  preliminaryAllowed: boolean;
  boundarySource: string;
}

export interface DirectOffer {
  id: string;
  variantId: string;
  priceXof: number;
  published: boolean;
  version: number;
  productName: string;
  description: string | null;
  attributes: Record<string, string>;
  saleUnit: string | null;
  usable: boolean;
  imageUrl: string | null;
}

export interface DirectQuoteLine {
  offerId: string;
  variantId: string;
  quantity: number;
  unitPriceXof: number;
  lineTotalXof: number;
  priceRevision: number;
  productName: string;
  attributes: Record<string, string>;
}

export interface DirectTrackingView {
  orderNumber: string;
  status: string;
  fulfillmentMethod: 'PICKUP' | 'DELIVERY';
  currency: 'XOF';
  goodsSubtotalXof: number;
  platformFeeXof: number;
  amountBeforeShippingXof: number;
  shippingFeeXof: number | null;
  acceptedTotalXof: number | null;
  paymentAvailable: false;
  placedAt: string;
}

export interface DirectCustomerFinancialStatus {
  orderNumber: string;
  currency: 'XOF';
  acceptedTotalXof: number | null;
  preparationStatus: string;
  paymentAvailable: boolean;
  financialStatus:
    | 'NOT_PREPARED' | 'PREPARED' | 'EXPIRED' | 'UNAVAILABLE'
    | 'SETTLEMENT_PROCESSING' | 'SETTLEMENT_VERIFIED' | 'REVIEW_REQUIRED';
}

export interface DirectPaymentAttempt {
  id: string;
  status: 'CREATED' | 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'EXPIRED';
  expectedAmount: number;
  currency: 'XOF';
  reconciliationRequired: boolean;
  initiatedAt: string;
  checkoutUrl: string | null;
}

export interface DirectDraftReceipt {
  orderNumber: string;
  status: 'PENDING_PAYMENT_INTEGRATION';
  currency: 'XOF';
  fulfillmentMethod: 'PICKUP' | 'DELIVERY';
  goodsSubtotalXof: number;
  platformFeeXof: number;
  amountBeforeShippingXof: number;
  shippingQuoted: boolean;
  shippingFeeXof: number | null;
  acceptedTotalXof: number | null;
  shippingRateVersion: number | null;
  paymentAvailable: false;
}

export interface DirectDraftCommand {
  contact: {
    firstName: string;
    lastName: string;
    phone: string;
    email: string | null;
    deliveryAddress: {
      country: 'SN';
      region: string | null;
      city: string | null;
      commune: string | null;
      neighborhood: string | null;
      details: string | null;
    } | null;
  };
  fulfillmentMethod: 'PICKUP' | 'DELIVERY';
  lines: {
    offerId: string;
    quantity: number;
    expectedUnitPriceXof: number;
    expectedPriceRevision: number;
  }[];
  deliveryPoint: { latitude: number; longitude: number } | null;
  acceptedTotalXof: number;
  recoveryToken: string;
}

export interface DirectCartQuote {
  currency: 'XOF';
  displayName: string;
  goodsSubtotalXof: number;
  lines: DirectQuoteLine[];
  checkoutAvailable: boolean;
  draftCreationAvailable?: boolean;
}

export interface DirectStorefront {
  publicIdentifier: string | null;
  displayName: string | null;
  currency: 'XOF';
  offers: DirectOffer[];
}

@Injectable({ providedIn: 'root' })
export class DirectSalesApi {
  private readonly http = inject(HttpClient);
  private privatePath(id: string) {
    return `/api/organizations/${encodeURIComponent(id)}/direct-store`;
  }
  manager(organizationId: string) {
    return firstValueFrom(
      this.http.get<DirectStorefront>(this.privatePath(organizationId)).pipe(timeout(15000)),
    );
  }
  rename(organizationId: string, displayName: string) {
    return firstValueFrom(
      this.http.put<DirectStorefront>(this.privatePath(organizationId), { displayName }),
    );
  }
  add(organizationId: string, variantId: string, priceXof: number) {
    return firstValueFrom(
      this.http.post<DirectStorefront>(this.privatePath(organizationId) + '/offers', {
        variantId,
        priceXof,
      }),
    );
  }
  update(organizationId: string, id: string, version: number, priceXof: number, published: boolean) {
    return firstValueFrom(
      this.http.put<DirectStorefront>(
        this.privatePath(organizationId) + '/offers/' + encodeURIComponent(id),
        { version, priceXof, published },
      ),
    );
  }
  quote(publicIdentifier: string, lines: { offerId: string; quantity: number }[]) {
    return firstValueFrom(
      this.http.post<DirectCartQuote>(
        '/api/public/direct-stores/' + encodeURIComponent(publicIdentifier) + '/quote',
        { lines },
      ).pipe(timeout(15000)),
    );
  }
  deliveryPolicy(organizationId: string) {
    return firstValueFrom(this.http.get<DirectDeliveryPolicy>(
      this.privatePath(organizationId) + '/delivery-policy',
    ).pipe(timeout(15000)));
  }
  updateDeliveryPolicy(organizationId: string, policy: DirectDeliveryPolicy) {
    return firstValueFrom(this.http.put<DirectDeliveryPolicy>(
      this.privatePath(organizationId) + '/delivery-policy', policy,
    ).pipe(timeout(15000)));
  }
  publicDeliveryPolicy(publicIdentifier: string) {
    return firstValueFrom(this.http.get<DirectDeliveryPolicy>(
      '/api/public/direct-stores/' + encodeURIComponent(publicIdentifier) + '/delivery-policy',
    ).pipe(timeout(15000)));
  }
  suggestDeliveryAddress(publicIdentifier: string, latitude: number, longitude: number) {
    return firstValueFrom(this.http.post<DirectAddressSuggestion>(
      '/api/public/direct-stores/' + encodeURIComponent(publicIdentifier) + '/reverse-geocode',
      { latitude, longitude },
    ).pipe(timeout(9000)));
  }

  previewDeliveryCoverage(publicIdentifier: string, latitude: number, longitude: number) {
    return firstValueFrom(this.http.post<DirectDeliveryCoverage>(
      '/api/public/direct-stores/' + encodeURIComponent(publicIdentifier) + '/delivery-coverage',
      { latitude, longitude },
    ).pipe(timeout(10000)));
  }

  shippingFees(organizationId: string) {
    return firstValueFrom(this.http.get<DirectShippingSettings>(
      this.privatePath(organizationId) + '/shipping-fees',
    ).pipe(timeout(15000)));
  }
  updateShippingFees(organizationId: string, settings: DirectShippingSettings) {
    return firstValueFrom(this.http.put<DirectShippingSettings>(
      this.privatePath(organizationId) + '/shipping-fees', settings,
    ).pipe(timeout(15000)));
  }
  shippingQuote(
    publicIdentifier: string,
    request: {
      fulfillmentMethod: 'PICKUP' | 'DELIVERY';
      lines: { offerId: string; quantity: number }[];
      region: string | null;
      deliveryPoint: { latitude: number; longitude: number } | null;
    },
  ) {
    return firstValueFrom(this.http.post<DirectShippingPreview>(
      '/api/public/direct-stores/' + encodeURIComponent(publicIdentifier) + '/shipping-quote',
      request,
    ).pipe(timeout(15000)));
  }

  createDraft(publicIdentifier: string, command: DirectDraftCommand, idempotencyKey: string) {
    return firstValueFrom(this.http.post<DirectDraftReceipt>(
      '/api/public/direct-stores/' + encodeURIComponent(publicIdentifier) + '/checkout',
      command,
      { headers: { 'Idempotency-Key': idempotencyKey } },
    ).pipe(timeout(20000)));
  }

  trackDirectOrder(token: string) {
    return firstValueFrom(this.http.post<DirectTrackingView>(
      '/api/public/direct-orders/lookup',
      { token },
    ).pipe(timeout(15000)));
  }

  directPaymentStatus(token: string) {
    return firstValueFrom(this.http.post<DirectCustomerFinancialStatus>(
      '/api/public/direct-orders/payment-status',
      { token },
    ).pipe(timeout(15000)));
  }

  initiateDirectPayment(token: string, idempotencyKey: string) {
    return firstValueFrom(this.http.post<DirectPaymentAttempt>(
      '/api/public/direct-orders/payments',
      { token },
      { headers: { 'Idempotency-Key': idempotencyKey } },
    ).pipe(timeout(25000)));
  }

  publicStore(publicIdentifier: string) {
    return firstValueFrom(
      this.http.get<DirectStorefront>(
        '/api/public/direct-stores/' + encodeURIComponent(publicIdentifier),
      ).pipe(timeout(15000)),
    );
  }
}
