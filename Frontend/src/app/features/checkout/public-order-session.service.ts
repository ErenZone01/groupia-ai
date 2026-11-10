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
/**
 * The backend is the source of truth for SASPAY_CHECKOUT_HOSTS and validates each
 * provider redirect before returning the private payment receipt. A second
 * frontend allowlist limited to pay.saspay.me would silently break Wave/Orange
 * when SasPay routes through an operator host.
 * This helper only enforces structural URL safety; it must never be used on
 * input supplied directly by a customer.
 */
export function safeSaspayCheckout(raw?: string): string | null {
  const candidate = safeCheckoutUrl(raw);
  if (!candidate) return null;
  const url = new URL(candidate);
  const host = url.hostname.toLowerCase();
  const excluded = ['.example', '.test', '.invalid', '.localhost'];
  if (
    !/^[a-z0-9]+(?:[.-][a-z0-9]+)*\.[a-z]{2,}$/.test(host) ||
    excluded.some((suffix) => host.endsWith(suffix)) ||
    url.hash ||
    (url.port && url.port !== '443')
  ) return null;
  return candidate;
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
