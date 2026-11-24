import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { DirectSalesApi } from './direct-sales.api';
import { DirectCartStore } from './direct-cart.store';
import { PublicDirectPage } from './public-direct.page';

const shopA = '11111111-1111-4111-8111-111111111111';
const shopB = '22222222-2222-4222-8222-222222222222';

describe('Vitrine publique : transparence des zones de livraison', () => {
  let params: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  let api: { publicStore: ReturnType<typeof vi.fn>; publicDeliveryPolicy: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    params = new BehaviorSubject(convertToParamMap({ publicIdentifier: shopA }));
    api = {
      publicStore: vi.fn(async (id: string) => ({
        publicIdentifier: id, displayName: 'Boutique démo', currency: 'XOF', offers: [],
      })),
      publicDeliveryPolicy: vi.fn(async () => ({
        mode: 'SELECTED', allowedRegions: ['Dakar', 'Thiès'], version: 4,
      })),
    };
    TestBed.configureTestingModule({
      providers: [
        DirectCartStore,
        { provide: DirectSalesApi, useValue: api },
        { provide: ActivatedRoute, useValue: { paramMap: params.asObservable() } },
      ],
    });
  });

  it('ne permet pas d’ajouter une offre épuisée au panier', async () => {
    const page = TestBed.runInInjectionContext(() => new PublicDirectPage());
    await vi.waitFor(() => expect(page.loading()).toBe(false));
    page.store.set({ publicIdentifier: shopA, displayName: 'Stock', currency: 'XOF', offers: [{
      id: 'stock-empty', variantId: 'variant', priceXof: 1000, published: true, version: 1,
      productName: 'Article', description: null, attributes: {}, saleUnit: 'UNIT', usable: true,
      imageUrl: null, stockAvailable: 0, stockVersion: 3,
    }] });
    const add = vi.spyOn(page.cart, 'add');
    page.addToCart('stock-empty');
    expect(add).not.toHaveBeenCalled();
  });

  it('charge les régions choisies par la gérante sur la vitrine, avant le panier', async () => {
    const page = TestBed.runInInjectionContext(() => new PublicDirectPage());
    await vi.waitFor(() => expect(page.deliveryPolicy()?.mode).toBe('SELECTED'));
    expect(api.publicDeliveryPolicy).toHaveBeenCalledWith(shopA);
    expect(page.deliveryPolicy()?.allowedRegions).toEqual(['Dakar', 'Thiès']);
    expect(page.store()?.displayName).toBe('Boutique démo');
  });

  it('ne promet pas une livraison nationale si la lecture publique des zones échoue', async () => {
    api.publicDeliveryPolicy.mockRejectedValue(new Error('offline'));
    const page = TestBed.runInInjectionContext(() => new PublicDirectPage());
    await vi.waitFor(() => expect(page.loading()).toBe(false));
    expect(page.deliveryPolicy()).toBeNull();
    expect(page.store()).not.toBeNull();
    expect(page.error()).toBe('');
  });

  it('efface les zones de la boutique précédente lors du changement de vitrine', async () => {
    const page = TestBed.runInInjectionContext(() => new PublicDirectPage());
    await vi.waitFor(() => expect(page.deliveryPolicy()?.allowedRegions).toContain('Dakar'));
    api.publicDeliveryPolicy.mockResolvedValue({
      mode: 'DISABLED', allowedRegions: [], version: 5,
    });
    params.next(convertToParamMap({ publicIdentifier: shopB }));
    await vi.waitFor(() => expect(page.deliveryPolicy()?.mode).toBe('DISABLED'));
    expect(api.publicDeliveryPolicy).toHaveBeenCalledWith(shopB);
    expect(page.store()?.publicIdentifier).toBe(shopB);
  });
});
