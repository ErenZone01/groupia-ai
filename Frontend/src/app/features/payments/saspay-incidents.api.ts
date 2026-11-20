import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom, timeout } from 'rxjs';

export type IncidentStatus = 'OPEN' | 'IN_REVIEW' | 'CLOSED';
export type IncidentDecisionAction = 'REVIEW_STARTED' | 'REVIEW_NOTE' | 'CLOSED';

export interface Incident {
  id: string;
  code: string;
  referenceHint: string;
  status: IncidentStatus;
  firstSeenAt: string;
  lastSeenAt: string;
  occurrenceCount: number;
  lastDecisionAt: string | null;
}

export interface IncidentDecision {
  id: string;
  action: IncidentDecisionAction;
  actorId: string;
  evidenceReference: string;
  reason: string;
  observedOccurrenceCount: number;
  recordedAt: string;
}

export interface IncidentDetail {
  incident: Incident;
  decisions: IncidentDecision[];
}

export interface AuditCheckpoint {
  completedUntil: string | null;
  lastSuccessAt: string | null;
  lastFailureCode: string | null;
}

export interface SaspayProviderPreflight {
  checkedAt: string;
  status: 'READ_ONLY_ACCESS_OK' | 'INCOMPLETE_SAMPLE' | 'NO_SN_XOF_BALANCE' | 'WALLET_FROZEN';
  sampledTransactions: number;
  historyHasMorePages: boolean;
  sampledBalanceRows: number;
  balancesComplete: boolean;
  senegalXofWallets: number;
  senegalAvailableXof: number;
  senegalPendingXof: number;
  walletFrozen: boolean;
  noteCode: 'DIAGNOSTIC_NOT_ACCOUNTING_PROOF';
}

export interface IncidentPage {
  items: Incident[];
  total: number;
  page: number;
  size: number;
  open: number;
  inReview: number;
  closed: number;
  checkpoint: AuditCheckpoint | null;
}

@Injectable({ providedIn: 'root' })
export class SaspayIncidentConsoleApi {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/platform/saspay/incidents';

  providerPreflight() {
    return firstValueFrom(
      this.http
        .get<SaspayProviderPreflight>(`${this.base}/provider-preflight`)
        .pipe(timeout(20000)),
    );
  }

  list(status: string, code: string, page: number, size = 20) {
    return firstValueFrom(
      this.http
        .get<IncidentPage>(this.base, {
          params: { status, code, page, size },
        })
        .pipe(timeout(15000)),
    );
  }

  get(id: string) {
    return firstValueFrom(
      this.http.get<IncidentDetail>(`${this.base}/${encodeURIComponent(id)}`).pipe(timeout(15000)),
    );
  }

  decide(
    id: string,
    action: IncidentDecisionAction,
    evidenceReference: string,
    reason: string,
    expectedOccurrenceCount: number,
  ) {
    return firstValueFrom(
      this.http
        .post<{ incidentId: string; decisionId: string; status: IncidentStatus }>(
          `${this.base}/${encodeURIComponent(id)}/decisions`,
          { action, evidenceReference, reason, expectedOccurrenceCount, confirmed: true },
        )
        .pipe(timeout(15000)),
    );
  }
}
