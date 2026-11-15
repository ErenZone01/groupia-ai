import {
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom, timeout } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { apiMessage } from '../../core/http/api-error';
import { money } from '../../shared/utils/display';

export interface CancellationView {
  caseId: string | null;
  status: string | null;
  orderStatus: string;
  orderVersion: number;
  canCancel: boolean;
  blocker: string | null;
}
export interface RefundDetail {
  refund: {
    id: string;
    status: string;
    version: number;
    currency: string;
    collectedPrincipal: number;
    previouslyRefunded: number;
    amountDue: number;
    legacyPlatformFee: number;
    reason: string;
    maskedReference: string | null;
  };
  canDeclare: boolean;
  providerRefundAvailable: boolean;
  historicalFeeCompensationDue: number;
}

@Component({
  selector: 'g-commercial-cancellation',
  imports: [FormsModule],
  templateUrl: './commercial-cancellation-panel.html',
  styles: `
    :host {
      display: block;
      margin-block: var(--s5);
    }
    section {
      padding: var(--s4);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      overflow-wrap: anywhere;
    }
    label {
      display: block;
      margin-block: var(--s3);
    }
    textarea,
    select,
    input:not([type='checkbox']) {
      display: block;
      width: 100%;
      max-width: 100%;
      box-sizing: border-box;
    }
    button {
      margin-block: var(--s3);
    }
    dt {
      font-weight: 600;
    }
    dd {
      margin: 0 0 var(--s3);
    }
  `,
})
export class CommercialCancellationPanel {
  readonly organizationId = input.required<string>();
  readonly orderId = input.required<string>();
  readonly target = input<'ORDER' | 'DIRECT_ORDER'>('ORDER');
  readonly changed = output<void>();
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private epoch = 0;
  readonly opened = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly view = signal<CancellationView | null>(null);
  readonly detail = signal<RefundDetail | null>(null);
  readonly money = money;
  reason = '';
  confirmed = false;
  externalMethod = 'WAVE';
  reference = '';
  note = '';
  declarationConfirmed = false;
  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.epoch++;
    });
    effect(() => {
      this.organizationId();
      this.orderId();
      this.target();
      this.auth.generation();
      untracked(() => {
        this.epoch++;
        this.opened.set(false);
        this.busy.set(false);
        this.view.set(null);
        this.detail.set(null);
        this.error.set('');
        this.reason = '';
        this.confirmed = false;
        this.reference = '';
        this.note = '';
        this.externalMethod = 'WAVE';
        this.declarationConfirmed = false;
      });
    });
  }
  private path() {
    return `/api/organizations/${this.organizationId()}/${this.target() === 'ORDER' ? 'orders' : 'direct-orders'}/${this.orderId()}/${this.target() === 'ORDER' ? 'cancellation' : 'paid-cancellation'}`;
  }
  private refundPath(id: string) {
    return `/api/organizations/${this.organizationId()}/commercial-refunds/${id}`;
  }
  private current(epoch: number, generation: number) {
    return epoch === this.epoch && generation === this.auth.generation();
  }
  async load() {
    if (this.busy()) return;
    this.opened.set(true);
    this.busy.set(true);
    this.error.set('');
    const epoch = this.epoch,
      generation = this.auth.generation();
    try {
      const view = await firstValueFrom(
        this.http.get<CancellationView>(this.path()).pipe(timeout(15000)),
      );
      if (!this.current(epoch, generation)) return;
      this.view.set(view);
      this.detail.set(null);
      if (view.caseId) {
        const detail = await firstValueFrom(
          this.http.get<RefundDetail>(this.refundPath(view.caseId)).pipe(timeout(15000)),
        );
        if (this.current(epoch, generation)) this.detail.set(detail);
      }
    } catch (error) {
      if (this.current(epoch, generation)) this.error.set(apiMessage(error));
    } finally {
      if (this.current(epoch, generation)) this.busy.set(false);
    }
  }
  private async intention(path: string, body: object, scope: string): Promise<string> {
    const hash = await crypto.subtle.digest(
      'SHA-256',
      new TextEncoder().encode(JSON.stringify(body)),
    );
    const fingerprint = Array.from(new Uint8Array(hash), (b) =>
      b.toString(16).padStart(2, '0'),
    ).join('');
    const slot = `groupia.commercial-refund:${scope}:${path}:${fingerprint}`;
    // Persist before submitting. If storage is unavailable, do not send an unrecoverable command.
    const prior = sessionStorage.getItem(slot);
    if (prior && /^[0-9a-f-]{36}$/i.test(prior)) return prior;
    const key = crypto.randomUUID();
    sessionStorage.setItem(slot, key);
    return key;
  }
  async cancel() {
    const view = this.view();
    if (this.busy() || !view?.canCancel || !this.confirmed || !this.reason.trim()) return;
    await this.submit(this.path(), {
      version: view.orderVersion,
      reason: this.reason.trim(),
      confirmed: true,
    });
  }
  async declare() {
    const detail = this.detail();
    if (this.busy() || !detail?.canDeclare || !this.declarationConfirmed) return;
    if (this.externalMethod === 'CASH' ? !this.note.trim() : !this.reference.trim()) return;
    await this.submit(this.refundPath(detail.refund.id) + '/external-declaration', {
      version: detail.refund.version,
      externalMethod: this.externalMethod,
      reference: this.reference.trim() || null,
      note: this.note.trim() || null,
      confirmed: true,
    });
  }
  private async submit(path: string, body: object) {
    const epoch = this.epoch,
      generation = this.auth.generation(),
      scope = this.auth.commandScope();
    if (!scope) {
      this.error.set('Reconnectez-vous avant de confirmer cette opération.');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    let success = false;
    try {
      const key = await this.intention(path, body, scope);
      if (!this.current(epoch, generation)) return;
      await firstValueFrom(
        this.http.post(path, body, { headers: { 'Idempotency-Key': key } }).pipe(timeout(15000)),
      );
      if (!this.current(epoch, generation)) return;
      success = true;
      this.confirmed = false;
      this.declarationConfirmed = false;
    } catch (error) {
      if (this.current(epoch, generation)) this.error.set(apiMessage(error));
    } finally {
      if (this.current(epoch, generation)) this.busy.set(false);
    }
    if (success && this.current(epoch, generation)) {
      await this.load();
      if (this.current(epoch, generation)) this.changed.emit();
    }
  }
}
