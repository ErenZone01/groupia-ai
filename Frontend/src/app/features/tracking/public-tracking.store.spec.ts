import { TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { PublicTrackingStore } from './public-tracking.store';
import { PublicOrderSessionService } from '../checkout/public-order-session.service';
import { CartStore } from '../checkout/cart.store';
import { orderStatus } from '../checkout/public-messages';
import { apiTestProviders } from '../../testing/api-test';
describe('Suivi et paiement publics', () => {
  let store: PublicTrackingStore;
  let http: HttpTestingController;
  let session: PublicOrderSessionService;
  let cart: CartStore;
  const receipt = {
    orderNumber: 'GRP-1',
    status: 'PENDING_PAYMENT',
    initialAmountDue: 5300,
    paymentDeadline: new Date(Date.now() + 900000).toISOString(),
  };
  beforeEach(() => {
    vi.useFakeTimers();
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: [...apiTestProviders(), PublicTrackingStore] });
    http = TestBed.inject(HttpTestingController);
    session = TestBed.inject(PublicOrderSessionService);
    cart = TestBed.inject(CartStore);
    session.save({
      orderNumber: 'GRP-1',
      trackingToken: 'secret-test',
      publicIdentifier: 'group',
      shippingLater: true,
      receipt,
    });
    cart.add(
      { publicIdentifier: 'group', orderable: true },
      {
        offerId: 'offer',
        unitPrice: 5000,
        priceRevision: 1,
        active: true,
        catalog: { usable: true },
      },
    );
  });
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.useRealTimers();
  });
  async function start(status = 'PENDING_PAYMENT') {
    store = TestBed.inject(PublicTrackingStore);
    await vi.advanceTimersByTimeAsync(0);
    http.expectOne('/api/public/orders/track').flush({ ...receipt, status });
  }
  it('pending reste en attente et conserve le panier', async () => {
    await start();
    expect(orderStatus(store.order()?.status)).toBe('Paiement en attente');
    expect(cart.count()).toBe(1);
  });
  it('seul track CONFIRMED vide le panier et arrête le polling', async () => {
    await start('CONFIRMED');
    expect(orderStatus(store.order()?.status)).toBe('Paiement confirmé');
    expect(cart.count()).toBe(0);
    await vi.advanceTimersByTimeAsync(12000);
    http.expectNone('/api/public/orders/track');
  });
  it('EXPIRED affiche expiration sans vider le panier', async () => {
    await start('EXPIRED');
    expect(orderStatus(store.order()?.status)).toBe('Délai de paiement expiré');
    expect(cart.count()).toBe(1);
    expect(store.polling()).toBe(false);
  });
  it('limite le polling même sans état final', async () => {
    await start();
    for (let i = 0; i < 44; i++) {
      await vi.advanceTimersByTimeAsync(4000);
      http.expectOne('/api/public/orders/track').flush(receipt);
    }
    await vi.advanceTimersByTimeAsync(10000);
    expect(store.polling()).toBe(false);
    http.expectNone('/api/public/orders/track');
  });
  it('arrête le polling à la destruction', async () => {
    await start();
    TestBed.resetTestingModule();
    await vi.advanceTimersByTimeAsync(20000);
    http.expectNone('/api/public/orders/track');
  });
  it('initiation réutilise la même clé après perte réseau et n’envoie aucun JWT', async () => {
    await start();
    const p = store.initiate();
    const first = http.expectOne('/api/public/orders/GRP-1/payments');
    const key = first.request.headers.get('Idempotency-Key');
    expect(first.request.headers.get('X-Order-Tracking-Token')).toBe('secret-test');
    expect(first.request.headers.has('Authorization')).toBe(false);
    expect(first.request.body).toEqual({ provider: 'bictorys' });
    first.error(new ProgressEvent('error'));
    await p;
    const retry = store.initiate();
    const second = http.expectOne('/api/public/orders/GRP-1/payments');
    expect(second.request.headers.get('Idempotency-Key')).toBe(key);
    second.flush({ status: 'PENDING', checkoutUrl: 'https://pay.example/checkout' });
    await retry;
    expect(store.checkoutUrl()).toBe('https://pay.example/checkout');
    expect(cart.count()).toBe(1);
  });
  it('reconciliationRequired interdit une autre initiation et masque le lien', async () => {
    await start();
    const p = store.initiate();
    http
      .expectOne('/api/public/orders/GRP-1/payments')
      .flush({
        status: 'PENDING',
        reconciliationRequired: true,
        checkoutUrl: 'https://pay.example',
      });
    await p;
    await store.initiate();
    http.expectNone('/api/public/orders/GRP-1/payments');
    expect(store.checkoutUrl()).toBeNull();
  });
  it('checkoutUrl null ne provoque aucune relance', async () => {
    await start();
    const p = store.initiate();
    http
      .expectOne('/api/public/orders/GRP-1/payments')
      .flush({ status: 'PENDING', checkoutUrl: null });
    await p;
    await store.initiate();
    http.expectNone('/api/public/orders/GRP-1/payments');
    expect(store.checkoutUrl()).toBeNull();
  });
  it('PaymentAttempt SUCCEEDED ne confirme pas la commande localement', async () => {
    await start();
    const p = store.initiate();
    http.expectOne('/api/public/orders/GRP-1/payments').flush({ status: 'SUCCEEDED' });
    await p;
    expect(store.order()?.status).toBe('PENDING_PAYMENT');
    expect(cart.count()).toBe(1);
  });
  it('perte du token ne lance aucune requête', async () => {
    session.save({ orderNumber: 'GRP-1', publicIdentifier: 'group', shippingLater: false });
    store = TestBed.inject(PublicTrackingStore);
    await vi.advanceTimersByTimeAsync(10000);
    http.expectNone('/api/public/orders/track');
    await store.initiate();
    http.expectNone('/api/public/orders/GRP-1/payments');
  });
});
