import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

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
  publicStore(publicIdentifier: string) {
    return firstValueFrom(
      this.http.get<DirectStorefront>(
        '/api/public/direct-stores/' + encodeURIComponent(publicIdentifier),
      ).pipe(timeout(15000)),
    );
  }
}
