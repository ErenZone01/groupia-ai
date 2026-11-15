import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom, timeout } from 'rxjs';
export interface CollectionRow {
  id: string;
  paymentRequestId: string;
  targetType: string;
  targetId: string;
  purpose: string;
  provider: string;
  accountReference: string;
  currency: string;
  sellerPrincipal: number;
  platformFee: number;
  collectionFee: number | null;
  customerCharged: number | null;
  confirmedAt: string;
  financialStatus: string;
  payoutStatus: string;
}
export interface CollectionAccountTotals {
  provider: string;
  accountReference: string;
  groupingCollected: number;
  directCollected: number;
  totalCollected: number;
}
export interface CollectionOverview {
  currency: 'XOF';
  groupingCollected: number;
  directCollected: number;
  totalCollected: number;
  directPayoutsEnabled: false;
  accounts: CollectionAccountTotals[];
}
export interface SimulationQuote {
  mode: 'SIMULATION';
  enabled: boolean;
  kind: 'PAYOUT' | 'REFUND';
  collectionId: string;
  currency: string;
  recipientNet: number;
  fee: number;
  simulatedCompanyFunding: number;
  reference: string;
  realOperationsEnabled: false;
}
export interface SimulationResult {
  id: string;
  collectionId: string;
  mode: 'SIMULATION';
  kind: 'PAYOUT' | 'REFUND';
  status: string;
  sourceProvider: string;
  accountReference: string;
  currency: string;
  recipientNet: number;
  fee: number;
  simulatedCompanyFunding: number;
  reference: string | null;
  createdAt: string;
  completedAt: string | null;
}
@Injectable({ providedIn: 'root' })
export class ProviderCollectionsApi {
  private readonly http = inject(HttpClient);
  private base(org: string) {
    return `/api/organizations/${encodeURIComponent(org)}/provider-collections`;
  }
  refresh(org: string, after?: string) {
    return firstValueFrom(
      this.http
        .post<{ recorded: number; nextCursor: string | null }>(
          this.base(org) + '/refresh',
          {},
          { params: after ? { after } : {} },
        )
        .pipe(timeout(15000)),
    );
  }
  page(org: string, after?: string) {
    return firstValueFrom(
      this.http
        .get<{
          items: CollectionRow[];
          nextCursor: string | null;
          canSimulatePayout: boolean;
          canSimulateRefund: boolean;
        }>(this.base(org), { params: after ? { after } : {} })
        .pipe(timeout(15000)),
    );
  }
  overview(org: string) {
    return firstValueFrom(
      this.http.get<CollectionOverview>(this.base(org) + '/overview').pipe(timeout(15000)),
    );
  }
  quote(org: string, id: string, kind: 'PAYOUT' | 'REFUND') {
    return firstValueFrom(
      this.http
        .get<SimulationQuote>(this.base(org) + `/${encodeURIComponent(id)}/simulation-quote`, {
          params: { kind },
        })
        .pipe(timeout(15000)),
    );
  }
  history(org: string, id: string) {
    return firstValueFrom(
      this.http
        .get<SimulationResult[]>(this.base(org) + `/${encodeURIComponent(id)}/simulations`)
        .pipe(timeout(15000)),
    );
  }
  start(org: string, q: SimulationQuote, key: string) {
    return firstValueFrom(
      this.http
        .post<SimulationResult>(
          this.base(org) + `/${encodeURIComponent(q.collectionId)}/simulations`,
          { kind: q.kind, quoteReference: q.reference, confirmed: true },
          { headers: { 'Idempotency-Key': key } },
        )
        .pipe(timeout(15000)),
    );
  }
  reconcile(org: string, id: string) {
    return firstValueFrom(
      this.http
        .post<SimulationResult>(
          `/api/organizations/${encodeURIComponent(org)}/marketplace-simulations/${encodeURIComponent(id)}/reconcile`,
          {},
        )
        .pipe(timeout(15000)),
    );
  }
}
