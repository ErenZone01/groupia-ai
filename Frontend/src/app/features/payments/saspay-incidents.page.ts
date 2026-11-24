import { DatePipe, DecimalPipe, registerLocaleData } from '@angular/common';
import localeFr from '@angular/common/locales/fr';
import { Component, LOCALE_ID, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { apiMessage } from '../../core/http/api-error';
import {
  Incident,
  IncidentDecisionAction,
  IncidentDetail,
  IncidentPage,
  SaspayProviderPreflight,
  SaspayAccountingSnapshot,
  SaspayIncidentConsoleApi,
} from './saspay-incidents.api';

registerLocaleData(localeFr);

@Component({
  selector: 'g-saspay-incidents-page',
  providers: [{ provide: LOCALE_ID, useValue: 'fr-FR' }],
  imports: [FormsModule, DatePipe, DecimalPipe, RouterLink],
  templateUrl: './saspay-incidents.page.html',
  styleUrl: './saspay-incidents.page.scss',
})
export class SaspayIncidentsPage {
  private readonly api = inject(SaspayIncidentConsoleApi);
  private readonly auth = inject(AuthService);
  readonly data = signal<IncidentPage | null>(null);
  readonly detail = signal<IncidentDetail | null>(null);
  readonly loading = signal(false);
  readonly inspecting = signal(false);
  readonly saving = signal(false);
  readonly error = signal('');
  readonly actionError = signal('');
  readonly notice = signal('');
  readonly providerPreflight = signal<SaspayProviderPreflight | null>(null);
  readonly preflightLoading = signal(false);
  readonly preflightError = signal('');
  private preflightVersion = 0;
  readonly accounting = signal<SaspayAccountingSnapshot | null>(null);
  readonly accountingLoading = signal(false);
  readonly accountingError = signal('');

  async loadAccounting() {
    if (this.accountingLoading()) return;
    const session = this.auth.generation();
    this.accountingLoading.set(true);
    this.accounting.set(null);
    this.accountingError.set('');
    try {
      const snapshot = await this.api.accounting();
      if (session === this.auth.generation()) this.accounting.set(snapshot);
    } catch (err) {
      if (session === this.auth.generation()) this.accountingError.set(apiMessage(err));
    } finally {
      if (session === this.auth.generation()) this.accountingLoading.set(false);
    }
  }

  walletGap(): number | null {
    const p = this.providerPreflight(), a = this.accounting();
    if (!p || !a || !p.balancesComplete || !p.senegalXofWallets ||
      Math.abs(Date.parse(p.checkedAt) - Date.parse(a.checkedAt)) > 60000) return null;
    return p.senegalAvailableXof + p.senegalPendingXof - a.knownNetWalletFlows;
  }

  potentialCoverageGap(): number | null {
    const p = this.providerPreflight(), a = this.accounting();
    if (!p || !a || this.walletGap() === null || p.walletFrozen) return null;
    return Math.max(0, a.retainedMerchantPrincipal + a.unallocatedCollections - p.senegalAvailableXof);
  }

  statusFilter = '';
  codeFilter = '';
  action: IncidentDecisionAction = 'REVIEW_STARTED';
  evidenceReference = '';
  reason = '';
  confirmed = false;
  private requestVersion = 0;
  private detailVersion = 0;

  readonly statusLabel = (status: string) =>
    ({
      OPEN: 'À examiner',
      IN_REVIEW: 'En cours de revue',
      CLOSED: 'Dossier clôturé',
    })[status] ?? 'À examiner';

  readonly codeLabel = (code: string) =>
    ({
      UNMATCHED_TRANSACTION: 'Transaction inconnue',
      INTERNAL_REFERENCE_NOT_OBSERVED: 'Référence interne absente de la fenêtre fournisseur',
      INVALID_INTERNAL_TRANSACTION_ID: 'Référence fournisseur interne invalide',
      FINANCIAL_OR_STATUS_MISMATCH: 'Montant ou statut incohérent',
      INVALID_FINANCIAL_FIELDS: 'Données financières invalides',
      WEBHOOK_STUCK: 'Webhook à examiner',
      PAYMENT_REVIEW_PENDING: 'Paiement à vérifier',
      PAYOUT_REVIEW_PENDING: 'Retrait à vérifier',
      PAYOUT_VERIFICATION_OVERDUE: 'Vérification de retrait en retard',
      AUDIT_INCOMPLETE: 'Synchronisation incomplète',
      DUPLICATE_TRANSACTION: 'Transaction en doublon',
      DUPLICATE_GROUPIA_REFERENCE: 'Référence en doublon',
      MONITORING_SCAN_OVERFLOW: 'Volume d’anomalies élevé',
    })[code] ?? code.replaceAll('_', ' ').toLowerCase();

  constructor() {
    void this.load();
  }

  private current(version: number, session: number) {
    return this.requestVersion === version && this.auth.generation() === session;
  }

  async load(page = 0) {
    const version = ++this.requestVersion;
    const session = this.auth.generation();
    this.loading.set(true);
    this.error.set('');
    this.notice.set('');
    this.detail.set(null);
    ++this.detailVersion;
    try {
      const data = await this.api.list(this.statusFilter, this.codeFilter.trim().toUpperCase(), page);
      if (this.current(version, session)) this.data.set(data);
    } catch (err) {
      if (this.current(version, session)) {
        this.data.set(null);
        this.error.set(apiMessage(err));
      }
    } finally {
      if (this.current(version, session)) this.loading.set(false);
    }
  }

  async checkProviderReadOnly() {
    if (this.preflightLoading()) return;
    const version = ++this.preflightVersion;
    const session = this.auth.generation();
    this.preflightLoading.set(true);
    this.providerPreflight.set(null);
    this.preflightError.set('');
    try {
      const snapshot = await this.api.providerPreflight();
      if (version === this.preflightVersion && session === this.auth.generation()) {
        this.providerPreflight.set(snapshot);
      }
    } catch (err) {
      if (version === this.preflightVersion && session === this.auth.generation()) {
        this.preflightError.set(apiMessage(err));
      }
    } finally {
      if (version === this.preflightVersion && session === this.auth.generation()) {
        this.preflightLoading.set(false);
      }
    }
  }

  async inspect(incident: Incident) {
    const version = ++this.detailVersion;
    const session = this.auth.generation();
    this.inspecting.set(true);
    this.actionError.set('');
    this.reason = '';
    this.evidenceReference = '';
    this.confirmed = false;
    this.action = incident.status === 'OPEN' ? 'REVIEW_STARTED' : 'REVIEW_NOTE';
    this.detail.set(null);
    try {
      const result = await this.api.get(incident.id);
      if (version === this.detailVersion && session === this.auth.generation())
        this.detail.set(result);
    } catch (err) {
      if (version === this.detailVersion && session === this.auth.generation())
        this.actionError.set(apiMessage(err));
    } finally {
      if (version === this.detailVersion && session === this.auth.generation())
        this.inspecting.set(false);
    }
  }

  closeDetail() {
    ++this.detailVersion;
    this.detail.set(null);
    this.actionError.set('');
    this.inspecting.set(false);
    this.confirmed = false;
  }

  async decide() {
    const item = this.detail()?.incident;
    if (!item || item.status === 'CLOSED' || this.saving() || !this.confirmed) return;
    const version = ++this.detailVersion;
    const session = this.auth.generation();
    this.saving.set(true);
    this.actionError.set('');
    try {
      await this.api.decide(
        item.id,
        this.action,
        this.evidenceReference.trim(),
        this.reason.trim(),
        item.occurrenceCount,
      );
      if (version !== this.detailVersion || session !== this.auth.generation()) return;
      await this.load(this.data()?.page ?? 0);
      if (session === this.auth.generation())
        this.notice.set('Décision administrative enregistrée. Aucun mouvement d’argent effectué.');
      // The list refresh clears the detail intentionally: new observations require a fresh review.
    } catch (err) {
      if (version === this.detailVersion && session === this.auth.generation())
        this.actionError.set(apiMessage(err));
    } finally {
      // Successful refresh deliberately closes the dossier and increments detailVersion.
      // Always release the submit lock for this authenticated session.
      if (session === this.auth.generation()) this.saving.set(false);
    }
  }

  previous() {
    const page = this.data()?.page ?? 0;
    if (page > 0) void this.load(page - 1);
  }

  next() {
    const data = this.data();
    if (data && (data.page + 1) * data.size < data.total) void this.load(data.page + 1);
  }
}
