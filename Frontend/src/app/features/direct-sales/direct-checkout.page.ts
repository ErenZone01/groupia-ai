import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DirectCartStore } from './direct-cart.store';
import { DirectSalesApi, DirectCartQuote } from './direct-sales.api';
import { PublicShell } from '../checkout/public-shell';
import { SENEGAL_REGIONS } from '../checkout/senegal-localities';
import { Input } from '../../shared/forms/input';
import { Select } from '../../shared/forms/select';
import { money, variantLabel } from '../../shared/utils/display';
import { apiMessage } from '../../core/http/api-error';

interface DirectCustomerLocation {
  country: 'SN';
  region: string;
  city: string;
  commune: string;
  neighborhood: string;
  details: string;
}

interface DirectCustomerContact {
  firstName: string;
  lastName: string;
  phone: string;
  email: string;
}

type FulfillmentMode = 'PICKUP' | 'DELIVERY';

/**
 * Preview of direct-sale checkout. Customer data stays in this component only;
 * no checkout write or payment request is performed until the backend rollout.
 */
@Component({
  selector: 'g-direct-checkout-page',
  imports: [FormsModule, RouterLink, PublicShell, Input, Select],
  templateUrl: './direct-checkout.page.html',
  styleUrl: './direct-checkout.page.scss',
})
export class DirectCheckoutPage {
  readonly cart = inject(DirectCartStore);
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(DirectSalesApi);
  readonly publicId = signal('');
  readonly quote = signal<DirectCartQuote | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly review = signal(false);
  readonly money = money;
  readonly variantLabel = variantLabel;
  readonly regions = SENEGAL_REGIONS;
  readonly other = '__OTHER__';

  contact: DirectCustomerContact = this.blankContact();
  location: DirectCustomerLocation = this.blankLocation();
  fulfillment: FulfillmentMode = 'PICKUP';
  cityChoice = '';
  communeChoice = '';
  private sequence = 0;

  constructor() {
    const sub = this.route.paramMap.subscribe((params) => {
      const id = params.get('publicIdentifier') ?? '';
      this.publicId.set(id);
      this.cart.forShop(id);
      this.resetPersonalData();
      void this.refresh();
    });
    inject(DestroyRef).onDestroy(() => {
      sub.unsubscribe();
      this.sequence++;
      this.resetPersonalData();
    });
  }

  get cities(): string[] {
    return this.regions.find((region) => region.name === this.location.region)?.departments ?? [];
  }

  get communes(): string[] {
    return this.regions.find((region) => region.name === this.location.region)?.communes ?? [];
  }

  changeRegion(region: string) {
    this.location.region = region;
    this.cityChoice = '';
    this.location.city = '';
    this.communeChoice = '';
    this.location.commune = '';
    this.review.set(false);
  }

  changeCity(value: string) {
    this.cityChoice = value;
    this.location.city = value === this.other ? '' : value;
    this.communeChoice = '';
    this.location.commune = '';
    this.review.set(false);
  }

  changeCommune(value: string) {
    this.communeChoice = value;
    this.location.commune = value === this.other ? '' : value;
    this.review.set(false);
  }

  changeFulfillment(value: FulfillmentMode) {
    this.fulfillment = value;
    this.review.set(false);
    if (value === 'PICKUP') {
      this.location = this.blankLocation();
      this.cityChoice = '';
      this.communeChoice = '';
    }
  }

  showReview() {
    if (this.quote() && !this.loading() && !this.error() && this.cart.items().length) {
      this.review.set(true);
    }
  }

  edit() {
    this.review.set(false);
  }

  async refresh() {
    const seq = ++this.sequence;
    const id = this.publicId();
    const items = this.cart.items();
    this.quote.set(null);
    this.error.set('');
    this.review.set(false);
    if (!id || !items.length) {
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    try {
      const quote = await this.api.quote(id, items);
      if (this.sequence === seq) this.quote.set(quote);
    } catch (error) {
      if (this.sequence === seq) this.error.set(apiMessage(error));
    } finally {
      if (this.sequence === seq) this.loading.set(false);
    }
  }

  private resetPersonalData() {
    this.contact = this.blankContact();
    this.location = this.blankLocation();
    this.cityChoice = '';
    this.communeChoice = '';
    this.fulfillment = 'PICKUP';
    this.review.set(false);
  }

  private blankContact(): DirectCustomerContact {
    return { firstName: '', lastName: '', phone: '', email: '' };
  }

  private blankLocation(): DirectCustomerLocation {
    return { country: 'SN', region: '', city: '', commune: '', neighborhood: '', details: '' };
  }
}
