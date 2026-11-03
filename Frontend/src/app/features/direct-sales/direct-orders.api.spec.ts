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
        shippingFeeXof: 0,
        acceptedTotalXof: 8800,
        shippingRateVersion: null,
        shippingQuoted: true,
        placedAt: '2026-10-31T12:00:00Z',
        itemCount: 1,
      },
      contact: { firstName: 'Awa', lastName: 'Diop', phoneE164: '+221771234567', email: null },
      deliveryAddress: null,
      platformFeeRule: { amount: '300' },
      items: [],
    });
    const result = await pending;
    expect(result.order.orderNumber).toBe('DIR-000000000001');
    expect(result.order.acceptedTotalXof).toBe(8800);
    expect(result.order.shippingFeeXof).toBe(0);
  });
  it('conserve les anciens brouillons sans transformer des frais absents en gratuité', async () => {
    const pending = api.list('boutique-1', 0);
    const request = http.expectOne(
      '/api/organizations/boutique-1/direct-orders?page=0&size=20',
    );
    request.flush({
      items: [{
        id: 'legacy-order',
        orderNumber: 'DIR-000000000002',
        status: 'PENDING_PAYMENT_INTEGRATION',
        fulfillmentMethod: 'DELIVERY',
        currency: 'XOF',
        goodsSubtotalXof: 8500,
        platformFeeXof: 300,
        amountBeforeShippingXof: 8800,
        shippingFeeXof: null,
        acceptedTotalXof: null,
        shippingRateVersion: null,
        shippingQuoted: false,
        placedAt: '2026-10-31T12:00:00Z',
        itemCount: 1,
      }],
      total: 1, page: 0, size: 20,
    });
    const page = await pending;
    expect(page.items[0].acceptedTotalXof).toBeNull();
    expect(page.items[0].shippingFeeXof).toBeNull();
    expect(page.items[0].amountBeforeShippingXof).toBe(8800);
  });

  it('lit l’état financier serveur sans envoyer les coordonnées du client', async () => {
    const pending = api.paymentStatus('boutique-1', 'commande-1');
    const request = http.expectOne(
      '/api/organizations/boutique-1/direct-orders/commande-1/payment-status',
    );
    expect(request.request.method).toBe('GET');
    expect(request.request.headers.has('X-Order-Tracking-Token')).toBe(false);
    request.flush({
      orderNumber: 'DIR-000000000001',
      acceptedTotalXof: 8800,
      financialStatus: 'SETTLEMENT_VERIFIED',
      fulfillmentAllowed: false,
    });
    const status = await pending;
    expect(status.financialStatus).toBe('SETTLEMENT_VERIFIED');
    expect(status.fulfillmentAllowed).toBe(false);
  });

  it('ne transmet ni montant, ni statut imposé, ni jeton client dans les actions', async () => {
    for (const [name, call] of [
      ['cancel', () => api.cancel('boutique-1', 'commande-1')],
      ['start-preparation', () => api.startPreparation('boutique-1', 'commande-1')],
      ['mark-ready', () => api.markReady('boutique-1', 'commande-1')],
    ] as const) {
      const pending = call();
      const request = http.expectOne(
        '/api/organizations/boutique-1/direct-orders/commande-1/' + name,
      );
      expect(request.request.method).toBe('POST');
      expect(request.request.body).toEqual({});
      expect(request.request.headers.has('X-Order-Tracking-Token')).toBe(false);
      request.flush({ orderNumber: 'DIR-000000000001', status: 'PREPARING' });
      expect((await pending).orderNumber).toBe('DIR-000000000001');
    }
  });

});
