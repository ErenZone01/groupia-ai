import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DirectOrdersApi } from './direct-orders.api';

describe('Commandes directes : accès commerçant', () => {
  let api: DirectOrdersApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(DirectOrdersApi);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  it('liste uniquement dans le contexte de l’organisation avec pagination', async () => {
    const pending = api.list('boutique-1', 2, 10);
    const request = http.expectOne(
      '/api/organizations/boutique-1/direct-orders?page=2&size=10',
    );
    expect(request.request.method).toBe('GET');
    expect(request.request.headers.has('X-Order-Tracking-Token')).toBe(false);
    request.flush({ items: [], total: 0, page: 2, size: 10 });
    expect((await pending).items).toEqual([]);
  });

  it('charge un détail privé sans utiliser les endpoints publics des groupages', async () => {
    const pending = api.get('boutique-1', 'commande-1');
    const request = http.expectOne(
      '/api/organizations/boutique-1/direct-orders/commande-1',
    );
    expect(request.request.method).toBe('GET');
    request.flush({
      order: {
        id: 'commande-1',
        orderNumber: 'DIR-000000000001',
        status: 'PENDING_PAYMENT_INTEGRATION',
        fulfillmentMethod: 'PICKUP',
        currency: 'XOF',
        goodsSubtotalXof: 8500,
        platformFeeXof: 300,
        amountBeforeShippingXof: 8800,
        shippingQuoted: true,
        placedAt: '2026-10-31T12:00:00Z',
        itemCount: 1,
      },
      contact: { firstName: 'Awa', lastName: 'Diop', phoneE164: '+221771234567', email: null },
      deliveryAddress: null,
      platformFeeRule: { amount: '300' },
      items: [],
    });
    expect((await pending).order.orderNumber).toBe('DIR-000000000001');
  });
});
