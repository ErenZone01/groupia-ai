import { Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import { apiMessage } from '../../core/http/api-error';
import { money, dateLabel } from '../../shared/utils/display';
import { Button } from '../../shared/ui/button';
import {
  ProviderCollectionsApi,
  CollectionRow,
  SimulationQuote,
  SimulationResult,
} from './provider-collections.api';
@Component({
  selector: 'g-provider-collections-page',
  imports: [FormsModule, RouterLink, Button],
  templateUrl: './provider-collections.page.html',
  styles: `
    article {
      padding: var(--s4);
      border: 1px solid var(--border);
      border-radius: var(--radius);
      margin: var(--s4) 0;
      min-width: 0;
    }
    p,
    h2,
    a {
      overflow-wrap: anywhere;
    }
    .actions {
      display: flex;
      gap: var(--s3);
      flex-wrap: wrap;
    }
    dl {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: var(--s2);
    }
    dd {
      text-align: right;
      margin: 0;
    }
    label {
      display: block;
      margin: var(--s4) 0;
    }
  `,
})
export class ProviderCollectionsPage {
  private readonly api = inject(ProviderCollectionsApi);
  private readonly auth = inject(AuthService);
  private readonly context = inject(BoutiqueContextService);
  readonly canPayout = signal(false);
  readonly canRefund = signal(false);
  readonly rows = signal<CollectionRow[]>([]);
  readonly next = signal<string | null>(null);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly selected = signal<CollectionRow | null>(null);
  readonly quote = signal<SimulationQuote | null>(null);
  readonly history = signal<SimulationResult[]>([]);
  readonly confirmed = signal(false);
  readonly money = money;
  readonly date = dateLabel;
  private epoch = 0;
  private operation = 0;
  private readonly keys = new Map<string, string>();
  constructor() {
    effect(() => {
      const org = this.context.selectedId();
      this.auth.generation();
      void this.load(org);
    });
  }
  private current(epoch: number, org: string, session: number) {
    return (
      this.epoch === epoch &&
      this.context.selectedId() === org &&
      this.auth.generation() === session
    );
  }
  async load(org = this.context.selectedId(), after?: string) {
    const epoch = ++this.epoch,
      session = this.auth.generation();
    this.operation++;
    this.busy.set(false);
    this.rows.set([]);
    this.canPayout.set(false);
    this.canRefund.set(false);
    this.next.set(null);
    this.selected.set(null);
    this.quote.set(null);
    this.history.set([]);
    this.error.set('');
    this.confirmed.set(false);
    this.loading.set(!!org);
    if (!org) return;
    try {
      if (!after) {
        let cursor: string | undefined;
        do {
          const result = await this.api.refresh(org, cursor);
          if (!this.current(epoch, org, session)) return;
          cursor = result.nextCursor ?? undefined;
        } while (cursor);
      }
      const result = await this.api.page(org, after);
      if (!this.current(epoch, org, session)) return;
      this.rows.set(result.items);
      this.canPayout.set(result.canSimulatePayout);
      this.canRefund.set(result.canSimulateRefund);
      this.next.set(result.nextCursor);
    } catch (e) {
      if (this.current(epoch, org, session)) this.error.set(apiMessage(e));
    } finally {
      if (this.current(epoch, org, session)) this.loading.set(false);
    }
  }
  async inspect(row: CollectionRow, kind?: 'PAYOUT' | 'REFUND') {
    const org = this.context.selectedId();
    if (!org || this.busy()) return;
    const op = ++this.operation,
      epoch = this.epoch,
      session = this.auth.generation();
    this.selected.set(row);
    this.quote.set(null);
    this.history.set([]);
    this.confirmed.set(false);
    this.error.set('');
    this.busy.set(true);
    try {
      const history = await this.api.history(org, row.id);
      const quote = kind ? await this.api.quote(org, row.id, kind) : null;
      if (op !== this.operation || !this.current(epoch, org, session)) return;
      this.history.set(history);
      this.quote.set(quote);
    } catch (e) {
      if (op === this.operation && this.current(epoch, org, session)) this.error.set(apiMessage(e));
    } finally {
      if (op === this.operation && this.current(epoch, org, session)) this.busy.set(false);
    }
  }
  async simulate() {
    const org = this.context.selectedId(),
      q = this.quote(),
      scope = this.auth.commandScope();
    if (
      !org ||
      !q?.enabled ||
      !scope ||
      !this.confirmed() ||
      this.busy() ||
      q.mode !== 'SIMULATION' ||
      q.realOperationsEnabled
    )
      return;
    const storageKey = `groupia.simulation:${scope}:${org}:${q.collectionId}:${q.kind}:${q.reference}`;
    let key = this.keys.get(storageKey);
    try {
      key ??= sessionStorage.getItem(storageKey) ?? undefined;
    } catch {
      /* Memory fallback. */
    }
    key ??= crypto.randomUUID();
    this.keys.set(storageKey, key);
    try {
      sessionStorage.setItem(storageKey, key);
    } catch {
      /* Server still enforces one reservation. */
    }
    await this.run(org, () => this.api.start(org, q, key));
  }
  async reconcile(item: SimulationResult) {
    const org = this.context.selectedId();
    if (!org || this.busy() || !['CREATED', 'REVIEW_REQUIRED'].includes(item.status)) return;
    await this.run(org, () => this.api.reconcile(org, item.id));
  }
  private async run(org: string, call: () => Promise<SimulationResult>) {
    const op = ++this.operation,
      epoch = this.epoch,
      session = this.auth.generation();
    this.busy.set(true);
    this.error.set('');
    this.confirmed.set(false);
    try {
      const result = await call();
      if (op !== this.operation || !this.current(epoch, org, session)) return;
      this.history.update((rows) => [...rows.filter((r) => r.id !== result.id), result]);
    } catch (e) {
      if (op === this.operation && this.current(epoch, org, session)) this.error.set(apiMessage(e));
    } finally {
      if (op === this.operation && this.current(epoch, org, session)) this.busy.set(false);
    }
  }
  readonly label = (s: string) =>
    ({
      CREATED: 'Simulation enregistrée',
      REVIEW_REQUIRED: 'Simulation à vérifier',
      SUCCEEDED: 'Simulation réussie',
      FAILED: 'Échec simulé confirmé',
    })[s] ?? 'Simulation à vérifier';
}
