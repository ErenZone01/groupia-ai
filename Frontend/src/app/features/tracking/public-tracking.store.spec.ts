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
    http.expectOne('/api/public/orders/GRP-1/payments').flush({
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
  it('présente le devis serveur et transmet seulement son identifiant après accord', async () => {
    await start();
    const loading = store.loadQuotes();
    const req = http.expectOne('/api/public/orders/GRP-1/payment-quotes');
    expect(req.request.headers.get('X-Order-Tracking-Token')).toBe('secret-test');
    const quote = {
      id: 'quote-1',
      provider: 'SASPAY',
      network: 'wave_sn',
      networkLabel: 'Wave',
      saleAmount: 5000,
      collectionFee: 125,
      totalCharged: 5125,
      merchantNet: 5000,
      groupiaCommission: 0,
      currency: 'XOF',
      expiresAt: new Date(Date.now() + 120000).toISOString(),
    };
    req.flush([quote]);
    await loading;
    http.expectNone('/api/public/orders/GRP-1/payments');
    const paying = store.initiate(store.feeQuotes()[0]);
    const payment = http.expectOne('/api/public/orders/GRP-1/payments');
    expect(payment.request.body).toEqual({ provider: 'SASPAY', quoteId: 'quote-1' });
    payment.flush({ status: 'PENDING', reconciliationRequired: false });
    await paying;
    expect(store.order()?.status).toBe('PENDING_PAYMENT');
    expect(cart.count()).toBe(1);
  });
  it('retrouve la même intention SasPay après rechargement sans nouveau devis', async () => {
    await start();
    session.update({ paymentKey: 'persisted-key', paymentQuoteId: 'accepted-quote' });
    await store.loadQuotes();
    http.expectNone('/api/public/orders/GRP-1/payment-quotes');
    await store.initiate();
    http.expectNone('/api/public/orders/GRP-1/payments');
    const retry = store.resumeInitiation();
    const req = http.expectOne('/api/public/orders/GRP-1/payments');
    expect(req.request.headers.get('Idempotency-Key')).toBe('persisted-key');
    expect(req.request.body).toEqual({ provider: 'SASPAY', quoteId: 'accepted-quote' });
    req.flush({ status: 'PENDING', reconciliationRequired: false });
    await retry;
    expect(store.order()?.status).toBe('PENDING_PAYMENT');
  });
  it('refuse un devis expiré et ne calcule jamais un montant de remplacement', async () => {
    await start();
    const quote = { id: 'expired', expiresAt: new Date(Date.now() - 1000).toISOString() };
    store.feeQuotes.set([quote]);
    await store.initiate(quote);
    http.expectNone('/api/public/orders/GRP-1/payments');
    expect(store.error()).toContain('expiré');
  });
  it('un échec de récupération des frais ne déclenche aucun paiement', async () => {
    await start();
    const loading = store.loadQuotes();
    http
      .expectOne('/api/public/orders/GRP-1/payment-quotes')
      .flush({}, { status: 503, statusText: 'Unavailable' });
    await loading;
    expect(store.feeQuotes()).toEqual([]);
    http.expectNone('/api/public/orders/GRP-1/payments');
  });
});
