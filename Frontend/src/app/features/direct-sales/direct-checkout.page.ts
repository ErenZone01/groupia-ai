import { DOCUMENT } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DirectCartStore } from './direct-cart.store';
import { DirectSalesApi, DirectCartQuote, DirectDeliveryPolicy, DirectAddressSuggestion, DirectDeliveryCoverage } from './direct-sales.api';
import { PublicShell } from '../checkout/public-shell';
import { SENEGAL_REGIONS } from '../checkout/senegal-localities';
import { Input } from '../../shared/forms/input';
import { Select } from '../../shared/forms/select';
import { money, variantLabel } from '../../shared/utils/display';
import { apiMessage } from '../../core/http/api-error';
import { DirectGeoPoint, DirectLocationMap, validDirectGeoPoint } from './direct-location-map';

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
type LocationMode = 'CHOICE' | 'MAP' | 'MANUAL' | 'CONFIRMED';

/**
 * Preview of direct-sale checkout. Customer data stays in this component only;
 * no checkout write or payment request is performed until the backend rollout.
 */
@Component({
  selector: 'g-direct-checkout-page',
  imports: [FormsModule, RouterLink, PublicShell, Input, Select, DirectLocationMap],
  templateUrl: './direct-checkout.page.html',
  styleUrl: './direct-checkout.page.scss',
})
export class DirectCheckoutPage {
  readonly cart = inject(DirectCartStore);
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(DirectSalesApi);
  private readonly browser = inject(DOCUMENT).defaultView;
  readonly publicId = signal('');
  readonly quote = signal<DirectCartQuote | null>(null);
  readonly deliveryPolicy = signal<DirectDeliveryPolicy | null>(null);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly review = signal(false);
  readonly locationMode = signal<LocationMode>('CHOICE');
  readonly candidatePoint = signal<DirectGeoPoint | null>(null);
  readonly confirmedPoint = signal<DirectGeoPoint | null>(null);
  readonly locating = signal(false);
  readonly locationError = signal('');
  readonly addressSuggestion = signal<DirectAddressSuggestion | null>(null);
  readonly addressLookupBusy = signal(false);
  readonly deliveryCoverage = signal<DirectDeliveryCoverage | null>(null);
  readonly coverageBusy = signal(false);
  readonly coverageError = signal(false);
  readonly money = money;
  readonly variantLabel = variantLabel;
  get regions() {
    const policy = this.deliveryPolicy();
    return policy?.mode === 'SELECTED'
      ? SENEGAL_REGIONS.filter((region) => policy.allowedRegions.includes(region.name))
      : SENEGAL_REGIONS;
  }

  get deliveryAvailable() {
    const policy = this.deliveryPolicy();
    return policy !== null && policy.mode !== 'DISABLED';
  }
  get locationReady(): boolean {
    if (this.fulfillment === 'PICKUP') return true;
    if (!this.deliveryAvailable) return false;
    if (this.locationMode() === 'CONFIRMED') {
      const status = this.deliveryCoverage()?.status;
      return this.confirmedPoint() !== null
        && !['OUTSIDE_ZONE', 'OUTSIDE_SENEGAL', 'DELIVERY_DISABLED'].includes(status ?? '');
    }
    return this.locationMode() === 'MANUAL'
      && this.regions.some((region) => region.name === this.location.region)
      && !!this.location.city.trim() && !!this.location.commune.trim()
      && !!this.location.details.trim();
  }
  readonly other = '__OTHER__';

  contact: DirectCustomerContact = this.blankContact();
  location: DirectCustomerLocation = this.blankLocation();
  fulfillment: FulfillmentMode = 'PICKUP';
  cityChoice = '';
  communeChoice = '';
  private sequence = 0;
  private locationSequence = 0;
  private addressLookupSequence = 0;
  private coverageSequence = 0;

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
    if (region && !this.regions.some((item) => item.name === region)) return;
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
    if (value === 'DELIVERY' && !this.deliveryAvailable) return;
    this.fulfillment = value;
    this.review.set(false);
    if (value === 'PICKUP') {
      this.clearMapLocation();
      this.location = this.blankLocation();
      this.cityChoice = '';
      this.communeChoice = '';
    }
  }

  manualAddress() {
    this.clearMapLocation();
    this.locationMode.set('MANUAL');
    this.review.set(false);
  }

  openMap() {
    this.locationMode.set('MAP');
    this.locationError.set('');
    this.review.set(false);
  }

  chooseMapPoint(point: DirectGeoPoint) {
    if (!validDirectGeoPoint(point)) return;
    this.addressLookupSequence++;
    this.addressSuggestion.set(null);
    this.addressLookupBusy.set(false);
    this.coverageSequence++;
    this.deliveryCoverage.set(null);
    this.coverageBusy.set(false);
    this.coverageError.set(false);
    this.candidatePoint.set(point);
    this.confirmedPoint.set(null);
    this.review.set(false);
  }

  confirmMapPoint() {
    const point = this.candidatePoint();
    if (!point || !validDirectGeoPoint(point) || !this.deliveryAvailable) return;
    this.confirmedPoint.set(point);
    // A previously entered manual address must not be mistaken for this new map point.
    this.location = this.blankLocation();
    this.cityChoice = '';
    this.communeChoice = '';
    this.locationMode.set('CONFIRMED');
    this.locationError.set('');
    this.review.set(false);
    void this.suggestAddress(point);
    void this.checkDeliveryCoverage(point);
  }

  private async checkDeliveryCoverage(point: DirectGeoPoint) {
    const seq = ++this.coverageSequence;
    const shop = this.publicId();
    this.deliveryCoverage.set(null);
    this.coverageError.set(false);
    this.coverageBusy.set(true);
    try {
      const coverage = await this.api.previewDeliveryCoverage(
        shop, point.latitude, point.longitude,
      );
      const selected = this.confirmedPoint();
      if (seq !== this.coverageSequence || shop !== this.publicId() ||
        this.locationMode() !== 'CONFIRMED' || !selected ||
        selected.latitude !== point.latitude || selected.longitude !== point.longitude) return;
      this.deliveryCoverage.set(coverage);
      this.review.set(false);
    } catch {
      if (seq === this.coverageSequence) this.coverageError.set(true);
    } finally {
      if (seq === this.coverageSequence) this.coverageBusy.set(false);
    }
  }

  get coverageMessage(): string {
    const result = this.deliveryCoverage();
    if (!result) return this.coverageError()
      ? 'Zone non vérifiée pour le moment. Vous pouvez choisir une adresse manuelle.'
      : 'La zone de livraison devra être confirmée.';
    switch (result.status) {
      case 'INSIDE_ZONE':
        return 'Ce point se trouve dans une région desservie. Livraison et frais à confirmer.';
      case 'OUTSIDE_ZONE':
      case 'OUTSIDE_SENEGAL':
        return 'Cette boutique ne livre pas à cet endroit. Choisissez un autre lieu ou le retrait.';
      case 'DELIVERY_DISABLED':
        return 'Cette boutique ne propose pas la livraison à domicile.';
      default:
        return 'Point proche d’une limite ou non reconnu. Choisissez un autre lieu ou une adresse manuelle.';
    }
  }

  private async suggestAddress(point: DirectGeoPoint) {
    const sequence = ++this.addressLookupSequence;
    const shop = this.publicId();
    this.addressSuggestion.set(null);
    this.addressLookupBusy.set(true);
    try {
      const suggestion = await this.api.suggestDeliveryAddress(
        shop, point.latitude, point.longitude,
      );
      if (sequence !== this.addressLookupSequence || shop !== this.publicId()) return;
      if (this.locationMode() !== 'CONFIRMED') return;
      const current = this.confirmedPoint();
      if (!current || current.latitude !== point.latitude || current.longitude !== point.longitude)
        return;
      if (suggestion.status === 'FOUND') this.addressSuggestion.set(suggestion);
    } catch {
      // Geocoding is best effort. An external outage must never block manual entry.
    } finally {
      if (sequence === this.addressLookupSequence) this.addressLookupBusy.set(false);
    }
  }

  get suggestedCoverageMessage(): string {
    const address = this.addressSuggestion();
    if (!address) return '';
    if (address.countryCode && address.countryCode !== 'SN')
      return 'Ce lieu semble situé hors du Sénégal. La livraison doit être confirmée.';
    if (address.region && !this.regions.some((region) => region.name === address.region))
      return 'Ce lieu semble être hors des régions proposées par cette boutique.';
    return 'Adresse estimée automatiquement. La livraison devra encore être confirmée.';
  }

  locateMe() {
    if (this.fulfillment !== 'DELIVERY' || !this.deliveryAvailable || this.locating()) return;
    const geolocation = this.browser?.navigator.geolocation;
    if (!geolocation) {
      this.locationError.set('Localisation indisponible. Choisissez un point sur la carte ou saisissez votre adresse.');
      return;
    }
    const seq = ++this.locationSequence;
    this.locating.set(true);
    this.locationError.set('');
    geolocation.getCurrentPosition(
      (position) => {
        if (seq !== this.locationSequence || this.fulfillment !== 'DELIVERY') return;
        this.locating.set(false);
        const point = {
          latitude: Number(position.coords.latitude.toFixed(6)),
          longitude: Number(position.coords.longitude.toFixed(6)),
        };
        if (!validDirectGeoPoint(point)) {
          this.locationError.set('Position invalide. Choisissez un point sur la carte.');
          return;
        }
        this.addressLookupSequence++;
        this.addressSuggestion.set(null);
        this.candidatePoint.set(point);
        this.confirmedPoint.set(null);
        this.locationMode.set('MAP');
        this.review.set(false);
      },
      () => {
        if (seq !== this.locationSequence) return;
        this.locating.set(false);
        this.locationError.set('Position non obtenue. Choisissez un point sur la carte ou saisissez votre adresse.');
      },
      { enableHighAccuracy: true, maximumAge: 0, timeout: 12000 },
    );
  }

  private clearMapLocation() {
    this.coverageSequence++;
    this.deliveryCoverage.set(null);
    this.coverageBusy.set(false);
    this.coverageError.set(false);
    this.addressLookupSequence++;
    this.addressSuggestion.set(null);
    this.addressLookupBusy.set(false);
    this.locationSequence++;
    this.locating.set(false);
    this.locationMode.set('CHOICE');
    this.candidatePoint.set(null);
    this.confirmedPoint.set(null);
    this.locationError.set('');
  }

  showReview() {
    if (this.quote() && this.deliveryPolicy() && !this.loading() && !this.error()
      && this.cart.items().length && this.locationReady) {
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
    this.deliveryPolicy.set(null);
    this.error.set('');
    this.review.set(false);
    if (!id || !items.length) {
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    try {
      const [quote, policy] = await Promise.all([
        this.api.quote(id, items),
        this.api.publicDeliveryPolicy(id),
      ]);
      if (this.sequence === seq) {
        this.deliveryPolicy.set(policy);
        this.quote.set(quote);
      }
    } catch (error) {
      if (this.sequence === seq) this.error.set(apiMessage(error));
    } finally {
      if (this.sequence === seq) this.loading.set(false);
    }
  }

  private resetPersonalData() {
    this.clearMapLocation();
    this.deliveryPolicy.set(null);
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
