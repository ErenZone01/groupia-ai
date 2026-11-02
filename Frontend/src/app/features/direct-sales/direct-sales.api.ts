import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export type DirectDeliveryMode = 'ALL' | 'SELECTED' | 'DISABLED';

export interface DirectDeliveryPolicy {
  mode: DirectDeliveryMode;
  allowedRegions: string[];
  version: number;
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

export interface DirectCartQuote {
  currency: 'XOF';
  displayName: string;
  goodsSubtotalXof: number;
  lines: DirectQuoteLine[];
  checkoutAvailable: boolean;
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

  publicStore(publicIdentifier: string) {
    return firstValueFrom(
      this.http.get<DirectStorefront>(
        '/api/public/direct-stores/' + encodeURIComponent(publicIdentifier),
      ).pipe(timeout(15000)),
    );
  }
}
