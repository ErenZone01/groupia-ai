import { DOCUMENT } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DirectCartStore } from './direct-cart.store';
import { DirectSalesApi, DirectCartQuote, DirectDeliveryPolicy, DirectAddressSuggestion, DirectDeliveryCoverage, DirectShippingPreview, DirectDraftCommand, DirectDraftReceipt } from './direct-sales.api';
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

/** Customer-side direct order creation; payment occurs separately via private tracking. */
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
  readonly shippingPreview = signal<DirectShippingPreview | null>(null);
  readonly shippingBusy = signal(false);
  readonly shippingError = signal(false);
  readonly submitting = signal(false);
  readonly submitError = signal('');
  readonly receipt = signal<DirectDraftReceipt | null>(null);
  readonly trackingToken = signal<string | null>(null);
  readonly amountAccepted = signal(false);
  private pendingDraft: { key: string; payload: DirectDraftCommand } | null = null;
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
  get pickupAvailable(): boolean {
    return !!this.quote()?.pickupAddress;
  }
  get locationReady(): boolean {
    if (this.fulfillment === 'PICKUP') return this.pickupAvailable;
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
  private shippingSequence = 0;

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
    void this.updateShippingPreview();
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
    if (value === 'PICKUP' && !this.pickupAvailable && this.fulfillment !== 'PICKUP') return;
    this.fulfillment = value;
    this.review.set(false);
    if (value === 'PICKUP') {
      this.clearMapLocation();
      this.location = this.blankLocation();
      this.cityChoice = '';
      this.communeChoice = '';
    }
    void this.updateShippingPreview();
  }

  manualAddress() {
    this.clearMapLocation();
    this.locationMode.set('MANUAL');
    this.review.set(false);
    void this.updateShippingPreview();
  }

  openMap() {
    this.locationMode.set('MAP');
    this.locationError.set('');
    this.review.set(false);
    void this.updateShippingPreview();
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
    void this.updateShippingPreview();
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
    void this.updateShippingPreview();
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

  async updateShippingPreview() {
    const seq = ++this.shippingSequence;
    const shop = this.publicId();
    const items = this.cart.items();
    this.shippingPreview.set(null);
    this.shippingError.set(false);
    this.shippingBusy.set(false);
    if (!this.quote() || !shop || !items.length) return;
    let region: string | null = null;
    let deliveryPoint: DirectGeoPoint | null = null;
    if (this.fulfillment === 'DELIVERY') {
      if (!this.deliveryAvailable) return;
      if (this.locationMode() === 'CONFIRMED') {
        deliveryPoint = this.confirmedPoint();
        if (!deliveryPoint) return;
      } else if (this.locationMode() === 'MANUAL') {
        region = this.location.region;
        if (!region) return;
      } else return;
    }
    this.shippingBusy.set(true);
    try {
      const preview = await this.api.shippingQuote(shop, {
        fulfillmentMethod: this.fulfillment,
        lines: items, region, deliveryPoint,
      });
      if (seq !== this.shippingSequence || shop !== this.publicId()) return;
      this.shippingPreview.set(preview);
    } catch {
      if (seq === this.shippingSequence) this.shippingError.set(true);
    } finally {
      if (seq === this.shippingSequence) this.shippingBusy.set(false);
    }
  }

  get shippingMessage(): string {
    if (this.shippingBusy()) return 'Calcul des frais de livraison…';
    if (this.shippingError()) return 'Tarif indisponible pour le moment. Aucun paiement ne sera demandé.';
    const preview = this.shippingPreview();
    if (!preview) return 'Choisissez votre mode de remise pour connaître les frais.';
    if (preview.status === 'PICKUP_UNAVAILABLE') return 'La boutique doit renseigner son point de retrait avant toute commande en retrait.';
    if (preview.status === 'FEE_UNCONFIGURED') return 'La gérante doit encore confirmer le tarif de cette région.';
    if (preview.status === 'OUTSIDE_ZONE' || preview.status === 'OUTSIDE_SENEGAL'
        || preview.status === 'DELIVERY_DISABLED') return 'Livraison indisponible à cet endroit.';
    if (preview.status !== 'QUOTED') return 'Ce point doit être vérifié avant la livraison.';
    return preview.locationVerification === 'MANUAL_UNVERIFIED'
      ? 'Montant estimatif : votre adresse manuelle reste à vérifier.'
      : 'Montant estimatif, sans réservation ni demande de paiement.';
  }

  get coverageMessage(): string {
    const result = this.deliveryCoverage();
    if (!result) return this.coverageError()
      ? 'Zone non vérifiée pour le moment. Vous pouvez choisir une adresse manuelle.'
      : 'La zone de livraison devra être confirmée.';
    switch (result.status) {
      case 'INSIDE_ZONE':
        return `Zone desservie : ${result.region ?? 'région vérifiée'}. Votre point est éligible selon les zones de la boutique. Les frais sont revérifiés avant la commande.`;
      case 'OUTSIDE_ZONE':
      case 'OUTSIDE_SENEGAL':
        return `Zone non desservie${result.region ? ' : ' + result.region : ''}. Impossible de commander en livraison à ce point : choisissez une autre destination ou le retrait.`;
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
    this.shippingSequence++;
    this.shippingPreview.set(null);
    this.shippingBusy.set(false);
    this.shippingError.set(false);
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

  get canSubmitDraft(): boolean {
    const price = this.shippingPreview();
    return !!this.quote()?.draftCreationAvailable
      && this.review() && !this.submitting() && !this.loading() && !this.error()
      && this.locationReady
      && !this.shippingBusy() && !this.shippingError()
      && price?.status === 'QUOTED' && price.shippingQuoted && price.totalXof !== null
      && this.amountAccepted()
      && (this.fulfillment === 'PICKUP' || this.locationMode() === 'MANUAL'
          || (this.locationMode() === 'CONFIRMED'
            && !this.coverageBusy() && this.deliveryCoverage()?.status === 'INSIDE_ZONE'));
  }

  showReview() {
    if (this.quote() && this.deliveryPolicy() && !this.loading() && !this.error()
      && this.cart.items().length && this.locationReady) {
      this.amountAccepted.set(false);
      this.submitError.set('');
      this.review.set(true);
    }
  }

  edit() {
    if (this.submitting()) return;
    this.pendingDraft = null;
    this.amountAccepted.set(false);
    this.submitError.set('');
    this.review.set(false);
  }

  private newRecoveryToken(): string | null {
    if (!this.browser?.crypto?.getRandomValues || !this.browser.btoa) return null;
    const entropy = this.browser.crypto.getRandomValues(new Uint8Array(32));
    return this.browser.btoa(String.fromCharCode(...entropy))
      .replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
  }

  private newDraftKey(): string | null {
    if (!this.browser?.crypto?.getRandomValues) return null;
    const entropy = this.browser.crypto.getRandomValues(new Uint8Array(16));
    return 'direct-' + Array.from(entropy, (byte) => byte.toString(16).padStart(2, '0')).join('');
  }

  async submitDraft() {
    if (!this.canSubmitDraft || this.receipt()) return;
    const shop = this.publicId();
    const quoted = this.quote();
    const price = this.shippingPreview();
    if (!quoted || !price || price.totalXof === null) return;
    if (!this.pendingDraft) {
      const key = this.newDraftKey();
      const recoveryToken = this.newRecoveryToken();
      if (!key || !recoveryToken) {
        this.submitError.set('Impossible de sécuriser la commande sur cet appareil.');
        return;
      }
      const lines = this.cart.items().map((item) => {
        const current = quoted.lines.find((line) => line.offerId === item.offerId);
        return current && current.quantity === item.quantity
          ? {
              offerId: item.offerId, quantity: item.quantity,
              expectedUnitPriceXof: current.unitPriceXof,
              expectedPriceRevision: current.priceRevision,
            }
          : null;
      });
      if (lines.some((line) => line === null)) {
        this.submitError.set('Le panier a changé. Actualisez les prix avant de confirmer.');
        return;
      }
      const point = this.fulfillment === 'DELIVERY' && this.locationMode() === 'CONFIRMED'
        ? this.confirmedPoint() : null;
      const deliveryAddress = this.fulfillment === 'PICKUP' ? null
        : this.locationMode() === 'MANUAL'
          ? { ...this.location }
          : {
              country: 'SN' as const, region: null, city: null, commune: null,
              neighborhood: null, details: this.location.details.trim() || null,
            };
      const payload: DirectDraftCommand = {
        contact: {
          firstName: this.contact.firstName.trim(), lastName: this.contact.lastName.trim(),
          phone: this.contact.phone.trim(), email: this.contact.email.trim() || null,
          deliveryAddress,
        },
        fulfillmentMethod: this.fulfillment,
        lines: lines.filter((line): line is NonNullable<typeof line> => line !== null),
        deliveryPoint: point,
        acceptedTotalXof: price.totalXof,
        recoveryToken,
      };
      this.pendingDraft = { key, payload };
    }
    const submission = this.pendingDraft;
    this.submitting.set(true);
    this.submitError.set('');
    let mustRefresh = false;
    try {
      const result = await this.api.createDraft(shop, submission.payload, submission.key);
      if (shop !== this.publicId()) return;
      this.receipt.set(result);
      this.trackingToken.set(submission.payload.recoveryToken);
      this.amountAccepted.set(false);
      this.pendingDraft = null;
      this.cart.clear(shop);
    } catch (error) {
      if (shop !== this.publicId()) return;
      if (error instanceof HttpErrorResponse && [400, 404, 409, 503].includes(error.status)) {
        // A definite rejection permits a new explicit acceptance with a new key.
        this.pendingDraft = null;
        this.amountAccepted.set(false);
        this.review.set(false);
        this.submitError.set(apiMessage(error) + ' Vérifiez les prix et acceptez le nouveau total.');
        mustRefresh = true;
      } else {
        // A timeout/network error is ambiguous: retry the *identical* request and key.
        this.submitError.set('Connexion interrompue. Réessayez sans modifier la commande : aucun doublon ne sera créé.');
      }
    } finally {
      if (shop === this.publicId()) this.submitting.set(false);
    }
    if (mustRefresh && shop === this.publicId()) await this.refresh();
  }

  async refresh() {
    if (this.submitting()) return;
    const seq = ++this.sequence;
    const id = this.publicId();
    const items = this.cart.items();
    this.quote.set(null);
    this.shippingSequence++;
    this.shippingPreview.set(null);
    this.shippingError.set(false);
    this.shippingBusy.set(false);
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
        if (!quote.pickupAddress && policy.mode !== 'DISABLED') {
          this.fulfillment = 'DELIVERY';
        }
        void this.updateShippingPreview();
      }
    } catch (error) {
      if (this.sequence === seq) this.error.set(apiMessage(error));
    } finally {
      if (this.sequence === seq) this.loading.set(false);
    }
  }

  private resetPersonalData() {
    this.pendingDraft = null;
    this.receipt.set(null);
    this.trackingToken.set(null);
    this.amountAccepted.set(false);
    this.submitError.set('');
    this.submitting.set(false);
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
