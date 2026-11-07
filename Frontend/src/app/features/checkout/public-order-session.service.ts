import { Injectable, signal } from '@angular/core';
import { OrderReceipt, PaymentAttemptView } from '../../api/generated/models';
export interface PublicOrderSession {
  orderNumber: string;
  trackingToken?: string;
  publicIdentifier: string;
  receipt?: OrderReceipt;
  payment?: PaymentAttemptView;
  paymentKey?: string;
  paymentQuoteId?: string;
  shippingLater: boolean;
}
export function safeCheckoutUrl(raw?: string): string | null {
  try {
    const url = new URL(raw!);
    return url.protocol === 'https:' && !url.username && !url.password ? url.href : null;
  } catch {
    return null;
  }
}
@Injectable({ providedIn: 'root' })
export class PublicOrderSessionService {
  private readonly key = 'groupia.public-order';
  readonly current = signal<PublicOrderSession | null>(this.restore());
  assertStorage() {
    const key = 'groupia.storage-check';
    sessionStorage.setItem(key, '1');
    sessionStorage.removeItem(key);
  }
  save(value: PublicOrderSession) {
    // Persist the one-time credential synchronously, before navigation or any other request.
    sessionStorage.setItem(this.key, JSON.stringify(value));
    this.current.set(value);
  }
  update(value: Partial<PublicOrderSession>) {
    const current = this.current();
    if (current) this.save({ ...current, ...value });
  }
  private restore(): PublicOrderSession | null {
    try {
      const s = JSON.parse(sessionStorage.getItem(this.key) ?? 'null');
      return s &&
        typeof s.orderNumber === 'string' &&
        typeof s.publicIdentifier === 'string' &&
        (!s.trackingToken || typeof s.trackingToken === 'string')
        ? s
        : null;
    } catch {
      return null;
    }
  }
}
