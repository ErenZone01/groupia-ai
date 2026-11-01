import { TestBed } from '@angular/core/testing';
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
  let api: { quote: ReturnType<typeof vi.fn>; publicDeliveryPolicy: ReturnType<typeof vi.fn> };
  let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;

  beforeEach(() => {
    params = new BehaviorSubject(convertToParamMap({ publicIdentifier: shopA }));
    api = {
      quote: vi.fn(async () => quoted),
      publicDeliveryPolicy: vi.fn(async () => ({ mode: 'ALL', allowedRegions: [], version: 0 })),
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
    expect(Object.keys(api)).toEqual(['quote', 'publicDeliveryPolicy']);
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
});
