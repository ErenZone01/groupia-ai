import { Component, ElementRef, inject, input, output, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom, timeout } from 'rxjs';
import {
  OrderCreditControllerService,
  RefundControllerService,
} from '../../api/generated/services';
import type { CreditResolution as CreditData, FulfillmentRow } from '../../api/generated/models';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import { Select } from '../../shared/forms/select';
import { RefundSummary } from '../../shared/ui/refund-summary';
import { apiMessage } from '../../core/http/api-error';
import { money } from '../../shared/utils/display';
@Component({
  selector: 'g-credit-resolution',
  imports: [FormsModule, Button, Input, Select, RefundSummary],
  templateUrl: './credit-resolution.html',
  styles: `
    :host {
      display: block;
      margin-top: var(--s4);
      min-width: 0;
    }
    dialog {
      width: min(32rem, calc(100vw - 2rem));
      max-height: 90dvh;
      overflow-y: auto;
      padding: var(--s5);
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      background: var(--surface);
      color: var(--text);
      margin: auto;
    }
    dialog::backdrop {
      background: color-mix(in srgb, var(--text) 40%, transparent);
    }
    h2,
    p,
    fieldset {
      margin-bottom: var(--s4);
    }
    fieldset {
      border: 1px solid var(--border);
      padding: var(--s3);
      border-radius: var(--radius-md);
      min-width: 0;
    }
    label {
      display: block;
      margin: var(--s3) 0;
    }
    .choice {
      display: flex;
      gap: var(--s3);
      align-items: flex-start;
    }
    input[type='radio'],
    input[type='checkbox'] {
      accent-color: var(--primary);
      flex-shrink: 0;
      width: 1.2rem;
      height: 1.2rem;
    }
    .actions {
      margin-top: var(--s4);
    }
    p {
      overflow-wrap: anywhere;
    }
  `,
})
export class CreditResolution {
  readonly organizationId = input.required<string>();
  readonly row = input.required<FulfillmentRow>();
  readonly disabled = input(false);
  readonly changed = output<void>();
  readonly data = signal<CreditData | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly modal = viewChild.required<ElementRef<HTMLDialogElement>>('modal');
  private readonly api = inject(OrderCreditControllerService);
  private readonly refunds = inject(RefundControllerService);
  readonly money = money;
  method = 'MANUAL_EXTERNAL';
  externalMethod = 'WAVE';
  reference = '';
  note = '';
  confirmed = false;
  async open() {
    if (this.disabled() || this.busy()) return;
    this.confirmed = false;
    this.reference = '';
    this.note = '';
    this.externalMethod = 'WAVE';
    this.data.set(null);
    this.error.set('');
    this.modal().nativeElement.showModal();
    await this.load();
  }
  private async load() {
    this.busy.set(true);
    try {
      this.data.set(
        await firstValueFrom(
          this.api
            .shippingCredit({ organizationId: this.organizationId(), orderId: this.row().orderId! })
            .pipe(timeout(15000)),
        ),
      );
      this.method = this.data()?.payoutsEnabled ? 'BICTORYS_PAYOUT' : 'MANUAL_EXTERNAL';
    } catch (e) {
      this.data.set(null);
      this.error.set(apiMessage(e));
    } finally {
      this.busy.set(false);
    }
  }
  close() {
    if (!this.busy()) {
      this.modal().nativeElement.close();
      this.data.set(null);
    }
  }
  cancel(event: Event) {
    if (this.busy()) event.preventDefault();
    else this.data.set(null);
  }
  selectMode() {
    this.externalMethod = 'WAVE';
    this.reference = '';
    this.note = '';
    this.confirmed = false;
  }
  valid(): boolean {
    return (
      !!this.data()?.canResolve &&
      this.confirmed &&
      (this.method === 'BICTORYS_PAYOUT'
        ? !!this.data()?.payoutsEnabled && ['WAVE', 'ORANGE_MONEY'].includes(this.externalMethod)
        : this.externalMethod === 'CASH'
          ? !!this.note.trim()
          : !!this.reference.trim())
    );
  }
  async submit() {
    if (this.busy() || this.disabled() || !this.valid() || this.row().version == null) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await firstValueFrom(
        this.api
          .resolveShippingCredit({
            organizationId: this.organizationId(),
            orderId: this.row().orderId!,
            body: {
              version: this.row().version!,
              method: this.method,
              externalMethod: this.externalMethod,
              confirmed: true,
              reference:
                this.method === 'MANUAL_EXTERNAL' ? this.reference.trim() || undefined : undefined,
              note: this.note.trim() || undefined,
            },
          })
          .pipe(timeout(40000)),
      );
      this.modal().nativeElement.close();
      this.data.set(null);
      this.changed.emit();
    } catch (e) {
      this.confirmed = false;
      this.error.set(apiMessage(e));
      await this.load();
    } finally {
      this.busy.set(false);
    }
  }
  async reconcile(id?: string) {
    if (!id || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await firstValueFrom(
        this.refunds
          .reconcileRefund({ organizationId: this.organizationId(), refundId: id })
          .pipe(timeout(40000)),
      );
      await this.load();
      if (this.data()?.settlement?.status === 'SETTLED') {
        this.modal().nativeElement.close();
        this.data.set(null);
        this.changed.emit();
      }
    } catch (e) {
      this.error.set(apiMessage(e));
    } finally {
      this.busy.set(false);
    }
  }
}
