import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { DirectCartStore } from './direct-cart.store';
import { DirectSalesApi, DirectCartQuote } from './direct-sales.api';
import { DirectCheckoutPage } from './direct-checkout.page';

const shopA = '11111111-1111-4111-8111-111111111111';
const shopB = '22222222-2222-4222-8222-222222222222';

const quoted: DirectCartQuote = {
  currency: 'XOF',
  displayName: 'Boutique Awa',
  goodsSubtotalXof: 9000,
  checkoutAvailable: false,
  lines: [{
    offerId: 'offer-a',
    variantId: 'variant-a',
    quantity: 1,
    unitPriceXof: 9000,
    lineTotalXof: 9000,
    priceRevision: 1,
    productName: 'Robe',
    attributes: {},
  }],
};

describe('Vente directe : préparation des coordonnées', () => {
  let cart: DirectCartStore;
  let api: { quote: ReturnType<typeof vi.fn>; publicDeliveryPolicy: ReturnType<typeof vi.fn>; suggestDeliveryAddress: ReturnType<typeof vi.fn>; previewDeliveryCoverage: ReturnType<typeof vi.fn>; shippingQuote: ReturnType<typeof vi.fn>; createDraft: ReturnType<typeof vi.fn> };
  let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;

  beforeEach(() => {
    params = new BehaviorSubject(convertToParamMap({ publicIdentifier: shopA }));
    api = {
      quote: vi.fn(async () => quoted),
      publicDeliveryPolicy: vi.fn(async () => ({ mode: 'ALL', allowedRegions: [], version: 0 })),
      suggestDeliveryAddress: vi.fn(async () => ({
        status: 'UNAVAILABLE', countryCode: null, region: null, city: null,
        neighborhood: null, formatted: null, provider: null,
      })),
      previewDeliveryCoverage: vi.fn(async () => ({
        status: 'INSIDE_ZONE', region: 'Dakar',
        preliminaryAllowed: true, boundarySource: 'geoBoundaries-SEN-ADM1-2019',
      })),
      shippingQuote: vi.fn(async () => ({
        status: 'QUOTED', currency: 'XOF', region: null,
        locationVerification: 'NONE', goodsSubtotalXof: 9000, platformFeeXof: 300,
        shippingFeeXof: 0, totalXof: 9300, shippingQuoted: true,
        paymentAvailable: false,
      })),
      createDraft: vi.fn(async () => ({
        orderNumber: 'DIR-000000000001', status: 'PENDING_PAYMENT_INTEGRATION',
        currency: 'XOF', fulfillmentMethod: 'PICKUP',
        goodsSubtotalXof: 9000, platformFeeXof: 300, amountBeforeShippingXof: 9300,
        shippingQuoted: true, shippingFeeXof: 0, acceptedTotalXof: 9300,
        shippingRateVersion: null, paymentAvailable: false,
      })),
    };
    TestBed.configureTestingModule({
      providers: [
        DirectCartStore,
        { provide: DirectSalesApi, useValue: api },
        { provide: ActivatedRoute, useValue: { paramMap: params.asObservable() } },
      ],
    });
    cart = TestBed.inject(DirectCartStore);
    cart.add(shopA, 'offer-a');
  });

  it('relit le prix côté serveur sans créer de commande ni demander un paiement', async () => {
    const page = TestBed.runInInjectionContext(() => new DirectCheckoutPage());
    await vi.waitFor(() => expect(page.quote()?.goodsSubtotalXof).toBe(9000));

    expect(api.quote).toHaveBeenCalledWith(shopA, [{ offerId: 'offer-a', quantity: 1 }]);
    expect(page.quote()?.checkoutAvailable).toBe(false);
    page.contact.firstName = 'Awa';
    page.contact.lastName = 'Diop';
    page.contact.phone = '+221771234567';
    page.showReview();
    expect(page.review()).toBe(true);
    page.edit();
    expect(page.review()).toBe(false);
    // The only API exposed to this page is a read-only cart quote.
    expect(api.publicDeliveryPolicy).toHaveBeenCalledWith(shopA);
    expect(Object.keys(api)).toEqual(['quote', 'publicDeliveryPolicy', 'suggestDeliveryAddress', 'previewDeliveryCoverage', 'shippingQuote', 'createDraft']);
  });

  it('réinitialise le formulaire, la localisation et le panier au changement de boutique', async () => {
    const page = TestBed.runInInjectionContext(() => new DirectCheckoutPage());
    await vi.waitFor(() => expect(page.quote()).not.toBeNull());

    page.contact.firstName = 'Awa';
    page.changeFulfillment('DELIVERY');
    page.changeRegion('Dakar');
    page.changeCity('Dakar');
    page.changeCommune('Yoff');
    page.location.details = 'Point de repère';
    expect(page.location.commune).toBe('Yoff');

    params.next(convertToParamMap({ publicIdentifier: shopB }));
    expect(page.publicId()).toBe(shopB);
    expect(page.contact.firstName).toBe('');
    expect(page.location.region).toBe('');
    expect(page.fulfillment).toBe('PICKUP');
    expect(page.review()).toBe(false);
    expect(cart.shopId()).toBe(shopB);
    expect(cart.items()).toEqual([]);
    expect(page.quote()).toBeNull();
    expect(api.quote).toHaveBeenCalledTimes(1);
  });

  it('efface la localisation de livraison après retour au retrait', () => {
    const page = TestBed.runInInjectionContext(() => new DirectCheckoutPage());
    page.changeFulfillment('DELIVERY');
    page.changeRegion('Dakar');
    page.changeCity('Pikine');
    page.changeCommune('Yoff');
    page.location.details = 'Maison';
    page.changeFulfillment('PICKUP');
    expect(page.location).toEqual({
      country: 'SN', region: '', city: '', commune: '', neighborhood: '', details: '',
    });
    expect(page.cityChoice).toBe('');
    expect(page.communeChoice).toBe('');
  });

  it('ne montre que les régions choisies par la gérante', async () => {
    api.publicDeliveryPolicy.mockResolvedValue({
      mode: 'SELECTED', allowedRegions: ['Dakar'], version: 1,
    });
    const page = TestBed.runInInjectionContext(() => new DirectCheckoutPage());
    await vi.waitFor(() => expect(page.deliveryPolicy()?.mode).toBe('SELECTED'));
    expect(page.regions.map((region) => region.name)).toEqual(['Dakar']);
    page.changeFulfillment('DELIVERY');
    page.changeRegion('Thiès');
    expect(page.location.region).toBe('');
    page.changeRegion('Dakar');
    expect(page.location.region).toBe('Dakar');
  });

  it('ne permet pas la livraison si la gérante l’a désactivée', async () => {
    api.publicDeliveryPolicy.mockResolvedValue({
      mode: 'DISABLED', allowedRegions: [], version: 2,
    });
    const page = TestBed.runInInjectionContext(() => new DirectCheckoutPage());
    await vi.waitFor(() => expect(page.deliveryPolicy()?.mode).toBe('DISABLED'));
    expect(page.deliveryAvailable).toBe(false);
    page.changeFulfillment('DELIVERY');
    expect(page.fulfillment).toBe('PICKUP');
  });
  it('autorise un point confirmé sur la carte sans imposer cinq champs', async () => {
    const page = TestBed.runInInjectionContext(() => new DirectCheckoutPage());
    await vi.waitFor(() => expect(page.quote()).not.toBeNull());
    page.changeFulfillment('DELIVERY');
    expect(page.locationReady).toBe(false);
    page.openMap();
    page.chooseMapPoint({ latitude: 92, longitude: -17.45 });
    expect(page.candidatePoint()).toBeNull();
    page.chooseMapPoint({ latitude: 14.73, longitude: -17.45 });
    page.confirmMapPoint();
    expect(page.locationMode()).toBe('CONFIRMED');
    expect(page.confirmedPoint()).toEqual({ latitude: 14.73, longitude: -17.45 });
    expect(page.locationReady).toBe(true);
    page.showReview();
    expect(page.review()).toBe(true);
    page.changeFulfillment('PICKUP');
    expect(page.confirmedPoint()).toBeNull();
    expect(page.locationMode()).toBe('CHOICE');
  });

  it('permet toujours la saisie manuelle en cas de GPS indisponible', async () => {
    const page = TestBed.runInInjectionContext(() => new DirectCheckoutPage());
    await vi.waitFor(() => expect(page.quote()).not.toBeNull());
    page.changeFulfillment('DELIVERY');
    page.manualAddress();
    page.changeRegion('Dakar');
    page.changeCity('Dakar');
    page.changeCommune('Yoff');
    page.location.details = 'Près du marché';
    expect(page.locationReady).toBe(true);
    page.showReview();
    expect(page.review()).toBe(true);
  });

  it('efface le point GPS lors du changement de boutique', async () => {
    const page = TestBed.runInInjectionContext(() => new DirectCheckoutPage());
    await vi.waitFor(() => expect(page.quote()).not.toBeNull());
    page.changeFulfillment('DELIVERY');
    page.openMap();
    page.chooseMapPoint({ latitude: 14.72, longitude: -17.46 });
    page.confirmMapPoint();
    params.next(convertToParamMap({ publicIdentifier: shopB }));
    expect(page.confirmedPoint()).toBeNull();
    expect(page.candidatePoint()).toBeNull();
    expect(page.locationMode()).toBe('CHOICE');
  });

  it('affiche une adresse suggérée sans la présenter comme zone vérifiée', async () => {
    api.suggestDeliveryAddress.mockResolvedValue({
      status: 'FOUND', countryCode: 'SN', region: 'Dakar', city: 'Dakar',
      neighborhood: 'Yoff', formatted: 'Yoff, Dakar, Sénégal', provider: 'geoapify',
    });
    const page = TestBed.runInInjectionContext(() => new DirectCheckoutPage());
    await vi.waitFor(() => expect(page.deliveryPolicy()).not.toBeNull());
    page.changeFulfillment('DELIVERY');
    page.openMap();
    page.chooseMapPoint({ latitude: 14.72, longitude: -17.46 });
    page.confirmMapPoint();
    await vi.waitFor(() => expect(page.addressSuggestion()?.region).toBe('Dakar'));
    expect(api.suggestDeliveryAddress).toHaveBeenCalledWith(shopA, 14.72, -17.46);
    expect(page.suggestedCoverageMessage).toContain('confirmée');
    page.changeFulfillment('PICKUP');
    expect(page.addressSuggestion()).toBeNull();
  });

  it('ignore le géocodage obsolète lors du déplacement du repère', async () => {
    let release!: (value: { status: string; countryCode: string; region: string; city: string; neighborhood: null; formatted: string; provider: string }) => void;
    api.suggestDeliveryAddress.mockImplementation(() => new Promise((resolve) => {
      release = resolve;
    }));
    const page = TestBed.runInInjectionContext(() => new DirectCheckoutPage());
    await vi.waitFor(() => expect(page.deliveryPolicy()).not.toBeNull());
    page.changeFulfillment('DELIVERY');
    page.chooseMapPoint({ latitude: 14.72, longitude: -17.46 });
    page.confirmMapPoint();
    expect(page.addressLookupBusy()).toBe(true);
    page.chooseMapPoint({ latitude: 14.74, longitude: -17.48 });
    release({
      status: 'FOUND', countryCode: 'SN', region: 'Dakar', city: 'Dakar',
      neighborhood: null, formatted: 'Ancien lieu', provider: 'geoapify',
    });
    await Promise.resolve();
    expect(page.addressSuggestion()).toBeNull();
    expect(page.addressLookupBusy()).toBe(false);
  });

  it('ne bloque pas la localisation en cas d’échec Geoapify', async () => {
    api.suggestDeliveryAddress.mockRejectedValue(new Error('provider offline'));
    const page = TestBed.runInInjectionContext(() => new DirectCheckoutPage());
    await vi.waitFor(() => expect(page.deliveryPolicy()).not.toBeNull());
    page.changeFulfillment('DELIVERY');
    page.chooseMapPoint({ latitude: 14.73, longitude: -17.44 });
    page.confirmMapPoint();
    await vi.waitFor(() => expect(page.addressLookupBusy()).toBe(false));
    expect(page.locationReady).toBe(true);
    expect(page.addressSuggestion()).toBeNull();
  });

  it('affiche la région vérifiée sur le serveur sans déclarer la livraison payée', async () => {
    const page = TestBed.runInInjectionContext(() => new DirectCheckoutPage());
    await vi.waitFor(() => expect(page.deliveryPolicy()).not.toBeNull());
    page.changeFulfillment('DELIVERY');
    page.openMap();
    page.chooseMapPoint({ latitude: 14.6937, longitude: -17.4474 });
    page.confirmMapPoint();
    await vi.waitFor(() => expect(page.deliveryCoverage()?.region).toBe('Dakar'));
    expect(api.previewDeliveryCoverage).toHaveBeenCalledWith(shopA, 14.6937, -17.4474);
    expect(page.coverageMessage).toContain('dans une région desservie');
    expect(page.locationReady).toBe(true);
  });

  it('bloque le récapitulatif lorsque les coordonnées sont hors zone', async () => {
    api.previewDeliveryCoverage.mockResolvedValue({
      status: 'OUTSIDE_ZONE', region: 'Thiès',
      preliminaryAllowed: false, boundarySource: 'geoBoundaries-SEN-ADM1-2019',
    });
    const page = TestBed.runInInjectionContext(() => new DirectCheckoutPage());
    await vi.waitFor(() => expect(page.deliveryPolicy()).not.toBeNull());
    page.changeFulfillment('DELIVERY');
    page.chooseMapPoint({ latitude: 14.791, longitude: -16.928 });
    page.confirmMapPoint();
    await vi.waitFor(() => expect(page.deliveryCoverage()?.status).toBe('OUTSIDE_ZONE'));
    expect(page.locationReady).toBe(false);
    page.showReview();
    expect(page.review()).toBe(false);
    page.changeFulfillment('PICKUP');
    expect(page.locationReady).toBe(true);
    expect(page.deliveryCoverage()).toBeNull();
  });

  it('ignore une réponse tardive des zones lorsque la cliente change de boutique', async () => {
    const page = TestBed.runInInjectionContext(() => new DirectCheckoutPage());
    await vi.waitFor(() => expect(page.deliveryPolicy()).not.toBeNull());
    page.changeFulfillment('DELIVERY');
    page.chooseMapPoint({ latitude: 14.72, longitude: -17.46 });
    page.confirmMapPoint();
    params.next(convertToParamMap({ publicIdentifier: shopB }));
    await vi.waitFor(() => expect(page.quote()).toBeNull());
    expect(page.deliveryCoverage()).toBeNull();
    expect(page.confirmedPoint()).toBeNull();
  });

  it('obtient un devis des frais serveur sur la région choisie sans paiement', async () => {
    api.shippingQuote.mockResolvedValue({
      status: 'QUOTED', currency: 'XOF', region: 'Dakar',
      locationVerification: 'MANUAL_UNVERIFIED',
      goodsSubtotalXof: 9000, platformFeeXof: 300,
      shippingFeeXof: 1500, totalXof: 10800,
      shippingQuoted: true, paymentAvailable: false,
    });
    const page = TestBed.runInInjectionContext(() => new DirectCheckoutPage());
    await vi.waitFor(() => expect(page.quote()).not.toBeNull());
    page.changeFulfillment('DELIVERY');
    page.manualAddress();
    page.changeRegion('Dakar');
    await vi.waitFor(() => expect(page.shippingPreview()?.shippingFeeXof).toBe(1500));
    expect(page.shippingPreview()?.totalXof).toBe(10800);
    expect(page.shippingPreview()?.paymentAvailable).toBe(false);
    expect(page.shippingMessage).toContain('adresse manuelle');
    expect(api.shippingQuote).toHaveBeenCalledWith(shopA, {
      fulfillmentMethod: 'DELIVERY',
      lines: [{ offerId: 'offer-a', quantity: 1 }],
      region: 'Dakar', deliveryPoint: null,
    });
    page.changeFulfillment('PICKUP');
    expect(page.shippingPreview()).toBeNull();
  });

  it('n’affiche pas de livraison gratuite si la vendeuse n’a pas fixé de tarif', async () => {
    api.shippingQuote.mockResolvedValue({
      status: 'FEE_UNCONFIGURED', currency: 'XOF', region: 'Dakar',
      locationVerification: 'MANUAL_UNVERIFIED', goodsSubtotalXof: 9000,
      platformFeeXof: 300, shippingFeeXof: null, totalXof: null,
      shippingQuoted: false, paymentAvailable: false,
    });
    const page = TestBed.runInInjectionContext(() => new DirectCheckoutPage());
    await vi.waitFor(() => expect(page.quote()).not.toBeNull());
    page.changeFulfillment('DELIVERY');
    page.manualAddress();
    page.changeRegion('Dakar');
    await vi.waitFor(() => expect(page.shippingPreview()?.status).toBe('FEE_UNCONFIGURED'));
    expect(page.shippingPreview()?.totalXof).toBeNull();
    expect(page.shippingMessage).toContain('confirmer');
  });

  it('garde la création de commande désactivée lorsque le serveur ne l’autorise pas', async () => {
    const page = TestBed.runInInjectionContext(() => new DirectCheckoutPage());
    await vi.waitFor(() => expect(page.shippingPreview()?.status).toBe('QUOTED'));
    page.contact = { firstName: 'Awa', lastName: 'Diop', phone: '+221771234567', email: '' };
    page.showReview();
    page.amountAccepted.set(true);
    expect(page.canSubmitDraft).toBe(false);
    await page.submitDraft();
    expect(api.createDraft).not.toHaveBeenCalled();
  });

  it('crée un seul brouillon sans paiement après acceptation du total serveur', async () => {
    api.quote.mockResolvedValue({ ...quoted, draftCreationAvailable: true });
    const page = TestBed.runInInjectionContext(() => new DirectCheckoutPage());
    await vi.waitFor(() => expect(page.shippingPreview()?.totalXof).toBe(9300));
    page.contact = { firstName: 'Awa', lastName: 'Diop', phone: '+221771234567', email: '' };
    page.showReview();
    expect(page.canSubmitDraft).toBe(false);
    page.amountAccepted.set(true);
    expect(page.canSubmitDraft).toBe(true);
    await page.submitDraft();
    expect(api.createDraft).toHaveBeenCalledTimes(1);
    const [shop, payload, key] = api.createDraft.mock.calls[0];
    expect(shop).toBe(shopA);
    expect(key).toMatch(/^direct-[0-9a-f]{32}$/);
    expect(payload).toEqual({
      contact: {
        firstName: 'Awa', lastName: 'Diop', phone: '+221771234567',
        email: null, deliveryAddress: null,
      },
      fulfillmentMethod: 'PICKUP',
      deliveryPoint: null,
      acceptedTotalXof: 9300,
      lines: [{
        offerId: 'offer-a', quantity: 1, expectedUnitPriceXof: 9000,
        expectedPriceRevision: 1,
      }],
    });
    expect(page.receipt()?.orderNumber).toBe('DIR-000000000001');
    expect(page.receipt()?.paymentAvailable).toBe(false);
    expect(cart.items()).toEqual([]);
  });

  it('réessaie strictement la même requête et clé après une coupure réseau', async () => {
    api.quote.mockResolvedValue({ ...quoted, draftCreationAvailable: true });
    api.createDraft.mockRejectedValueOnce(new Error('network connection lost'));
    const page = TestBed.runInInjectionContext(() => new DirectCheckoutPage());
    await vi.waitFor(() => expect(page.shippingPreview()?.status).toBe('QUOTED'));
    page.contact = { firstName: 'Awa', lastName: 'Diop', phone: '+221771234567', email: '' };
    page.showReview();
    page.amountAccepted.set(true);
    await page.submitDraft();
    expect(page.receipt()).toBeNull();
    expect(page.submitError()).toContain('Réessayez');
    expect(page.canSubmitDraft).toBe(true);
    await page.submitDraft();
    expect(api.createDraft).toHaveBeenCalledTimes(2);
    expect(api.createDraft.mock.calls[0]).toEqual(api.createDraft.mock.calls[1]);
    expect(page.receipt()?.orderNumber).toBe('DIR-000000000001');
  });

  it('refuse de réessayer automatiquement un ancien total après un conflit serveur', async () => {
    api.quote.mockResolvedValue({ ...quoted, draftCreationAvailable: true });
    api.createDraft.mockRejectedValueOnce(new HttpErrorResponse({
      status: 409,
      error: { code: 'DIRECT_TOTAL_CHANGED', message: 'Le total a changé.' },
    }));
    const page = TestBed.runInInjectionContext(() => new DirectCheckoutPage());
    await vi.waitFor(() => expect(page.shippingPreview()?.status).toBe('QUOTED'));
    page.contact = { firstName: 'Awa', lastName: 'Diop', phone: '+221771234567', email: '' };
    page.showReview();
    page.amountAccepted.set(true);
    await page.submitDraft();
    expect(api.createDraft).toHaveBeenCalledTimes(1);
    expect(page.receipt()).toBeNull();
    expect(page.amountAccepted()).toBe(false);
    expect(page.review()).toBe(false);
    expect(page.submitError()).toContain('Vérifiez');
  });

});
