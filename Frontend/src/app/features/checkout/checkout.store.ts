import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { GroupingCheckoutContact, CheckoutCommand } from '../../api/generated/models';
import { GroupiaApi } from '../../api/groupia-api';
import { apiCode } from '../../core/http/api-error';
import { CartStore } from './cart.store';
import { PublicOrderApi } from './public-order-api';
import { PublicOrderSessionService } from './public-order-session.service';
import { publicMessage } from './public-messages';
export interface CustomerLocation {
  country: string;
  region: string;
  city: string;
  commune: string;
  neighborhood?: string;
  details: string;
}

export type CheckoutContact = GroupingCheckoutContact & { deliveryAddress?: CustomerLocation };

export function normalizeContact(c: CheckoutContact): CheckoutContact {
  return {
    firstName: c.firstName?.trim(),
    lastName: c.lastName?.trim(),
    phone: c.phone?.replace(/[\s().-]/g, ''),
    ...(c.email?.trim() ? { email: c.email.trim().toLowerCase() } : {}),
    ...(c.deliveryAddress ? { deliveryAddress: {
      country: c.deliveryAddress.country.trim(),
      region: c.deliveryAddress.region.trim(),
      city: c.deliveryAddress.city.trim(),
      commune: c.deliveryAddress.commune.trim(),
      ...(c.deliveryAddress.neighborhood?.trim()
        ? { neighborhood: c.deliveryAddress.neighborhood.trim() } : {}),
      details: c.deliveryAddress.details.trim(),
    } } : {}),
  };
}
@Injectable({ providedIn: 'root' })
export class CheckoutStore {
  private readonly api = inject(PublicOrderApi);
  private readonly groups = inject(GroupiaApi);
  readonly cart = inject(CartStore);
  readonly session = inject(PublicOrderSessionService);
  readonly busy = signal(false);
  readonly error = signal('');
  async submit(contact: CheckoutContact): Promise<boolean> {
    if (this.busy()) return false;
    this.busy.set(true);
    this.error.set('');
    const id = this.cart.publicIdentifier();
    try {
      this.session.assertStorage();
      const body: CheckoutCommand = {
        contact: normalizeContact(contact),
        lines: this.cart.lines(),
      };
      const fingerprint = Array.from(
        new Uint8Array(
          await crypto.subtle.digest(
            'SHA-256',
            new TextEncoder().encode(JSON.stringify({ id, body })),
          ),
        ),
      )
        .map((b) => b.toString(16).padStart(2, '0'))
        .join('');
      const previous = JSON.parse(sessionStorage.getItem('groupia.checkout-attempt') ?? 'null');
      const key = previous?.fingerprint === fingerprint ? previous.key : crypto.randomUUID();
      sessionStorage.setItem('groupia.checkout-attempt', JSON.stringify({ fingerprint, key }));
      const group = await this.groups.publicGrouping(id);
      this.cart.reconcile(group);
      if (!this.cart.ready()) {
        this.error.set(
          'Votre panier a changé ou le groupage est fermé. Vérifiez le panier avant de continuer.',
        );
        return false;
      }
      const response = await firstValueFrom(this.api.checkout(id, key, body));
      if (!response.body.orderNumber) throw new Error('Invalid receipt');
      const existing = this.session.current();
      if (response.created && response.body.trackingToken) {
        this.session.save({
          orderNumber: response.body.orderNumber,
          trackingToken: response.body.trackingToken,
          publicIdentifier: id,
          receipt: { ...response.body, trackingToken: undefined },
          shippingLater: group.shippingTerms?.shippingCollectionMode === 'AFTER_FINAL_ASSESSMENT',
        });
      } else if (existing?.orderNumber !== response.body.orderNumber) {
        this.session.save({
          orderNumber: response.body.orderNumber,
          publicIdentifier: id,
          shippingLater: false,
        });
      }
      return true;
    } catch (e) {
      this.error.set(publicMessage(e));
      if (
        [
          'GROUPING_OFFER_PRICE_CHANGED',
          'GROUPING_OFFER_PRICE_CONFLICT',
          'GROUPING_OFFER_NOT_AVAILABLE',
        ].includes(apiCode(e))
      ) {
        try {
          this.cart.reconcile(await this.groups.publicGrouping(id));
        } catch {
          /* Keep the accepted snapshots. */
        }
      }
      return false;
    } finally {
      this.busy.set(false);
    }
  }
}
