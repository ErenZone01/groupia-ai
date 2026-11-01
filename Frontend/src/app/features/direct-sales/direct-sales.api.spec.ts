import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DirectSalesApi } from './direct-sales.api';

describe('Vente directe : contrats HTTP', () => {
  let api: DirectSalesApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(DirectSalesApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const org = '11111111-1111-4111-8111-111111111111';
  const storefront = {
    publicIdentifier: '22222222-2222-4222-8222-222222222222',
    displayName: 'Boutique Awa',
    currency: 'XOF',
    offers: [],
  } as const;

  it('lit le magasin dans le contexte de la boutique', async () => {
    const pending = api.manager(org);
    const request = http.expectOne(`/api/organizations/${org}/direct-store`);
    expect(request.request.method).toBe('GET');
    request.flush(storefront);
    expect((await pending).displayName).toBe('Boutique Awa');
  });

  it('conserve le prix fixe et la version dans une publication', async () => {
    const pending = api.update(org, '33333333-3333-4333-8333-333333333333', 7, 8500, true);
    const req = http.expectOne(
      `/api/organizations/${org}/direct-store/offers/33333333-3333-4333-8333-333333333333`,
    );
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ version: 7, priceXof: 8500, published: true });
    req.flush(storefront);
    await expect(pending).resolves.toMatchObject({ publicIdentifier: storefront.publicIdentifier });
  });

  it('cote le panier à partir du serveur sans prix fourni par le client', async () => {
    const pending = api.quote(storefront.publicIdentifier, [{ offerId: 'offre-1', quantity: 2 }]);
    const req = http.expectOne('/api/public/direct-stores/' + storefront.publicIdentifier + '/quote');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ lines: [{ offerId: 'offre-1', quantity: 2 }] });
    req.flush({
      currency: 'XOF',
      displayName: 'Boutique Awa',
      goodsSubtotalXof: 17000,
      checkoutAvailable: false,
      lines: [{
        offerId: 'offre-1',
        variantId: 'variante-1',
        quantity: 2,
        unitPriceXof: 8500,
        lineTotalXof: 17000,
        priceRevision: 1,
        productName: 'Sac en cuir',
        attributes: {},
      }],
    });
    expect((await pending).goodsSubtotalXof).toBe(17000);
  });

  it('le lien public ne nécessite pas de contexte commerçant', async () => {
    const pending = api.publicStore(storefront.publicIdentifier);
    const req = http.expectOne('/api/public/direct-stores/' + storefront.publicIdentifier);
    expect(req.request.method).toBe('GET');
    req.flush(storefront);
    expect((await pending).offers).toEqual([]);
  });
  it('enregistre les régions de livraison et la version de configuration', async () => {
    const policy = { mode: 'SELECTED' as const, allowedRegions: ['Dakar'], version: 0 };
    const pending = api.updateDeliveryPolicy(org, policy);
    const request = http.expectOne('/api/organizations/' + org + '/direct-store/delivery-policy');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual(policy);
    request.flush({ ...policy, version: 1 });
    expect((await pending).version).toBe(1);
  });

  it('lit la couverture publique sans transmettre de coordonnées GPS', async () => {
    const pending = api.publicDeliveryPolicy(storefront.publicIdentifier);
    const request = http.expectOne(
      '/api/public/direct-stores/' + storefront.publicIdentifier + '/delivery-policy',
    );
    expect(request.request.method).toBe('GET');
    request.flush({ mode: 'DISABLED', allowedRegions: [], version: 2 });
    expect((await pending).mode).toBe('DISABLED');
  });

});
