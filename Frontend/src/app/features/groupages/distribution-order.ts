import { Component, effect, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import type { FulfillmentRow } from '../../api/generated/models';
import { Button } from '../../shared/ui/button';
import { Input } from '../../shared/forms/input';
import { ShippingSettlement } from '../../shared/ui/shipping-settlement';
import { dateLabel } from '../../shared/utils/display';
import { physicalLabel, blockerLabel, historyLabel } from './distribution-labels';
export interface DistributionAction {
  row: FulfillmentRow;
  type: 'ready' | 'revoke' | 'pickup';
  reason?: string;
}
@Component({
  selector: 'g-distribution-order',
  imports: [FormsModule, Button, Input, ShippingSettlement],
  templateUrl: './distribution-order.html',
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    article {
      border: 1px solid var(--border);
      border-radius: var(--radius-lg);
      padding: var(--s5);
      background: var(--surface);
    }
    h3,
    p {
      overflow-wrap: anywhere;
      margin-bottom: var(--s2);
    }
    .confirmation {
      background: var(--surface-alt);
      padding: var(--s4);
      border-radius: var(--radius-md);
      margin-top: var(--s4);
    }
    .attestation {
      display: flex;
      gap: var(--s3);
      align-items: flex-start;
      margin-bottom: var(--s4);
    }
    input[type='checkbox'] {
      width: 1.25rem;
      height: 1.25rem;
      flex-shrink: 0;
      accent-color: var(--primary);
    }
    details {
      margin-top: var(--s4);
    }
    summary {
      cursor: pointer;
    }
    li {
      margin: var(--s3) 0;
      overflow-wrap: anywhere;
    }
  `,
})
export class DistributionOrder {
  readonly row = input.required<FulfillmentRow>();
  readonly refreshVersion = input(0);
  constructor() {
    effect(() => {
      this.row();
      this.refreshVersion();
      this.mode.set(null);
      this.confirmed = false;
      this.reason = '';
    });
  }
  readonly disabled = input(false);
  readonly action = output<DistributionAction>();
  readonly mode = signal<DistributionAction['type'] | null>(null);
  confirmed = false;
  reason = '';
  readonly physicalLabel = physicalLabel;
  readonly blockerLabel = blockerLabel;
  readonly historyLabel = historyLabel;
  readonly dateLabel = dateLabel;
  open(type: DistributionAction['type']) {
    if (this.disabled()) return;
    this.confirmed = false;
    this.reason = '';
    this.mode.set(type);
  }
  cancel() {
    if (!this.disabled()) this.mode.set(null);
  }
  submit() {
    const type = this.mode();
    if (!type || this.disabled() || (type === 'revoke' ? !this.reason.trim() : !this.confirmed))
      return;
    this.action.emit({
      row: this.row(),
      type,
      reason: type === 'revoke' ? this.reason.trim() : undefined,
    });
  }
}
