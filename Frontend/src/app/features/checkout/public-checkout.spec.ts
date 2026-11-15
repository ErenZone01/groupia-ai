import { TestBed } from '@angular/core/testing';
import { HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { CartStore } from './cart.store';
import { CheckoutContact, CheckoutStore } from './checkout.store';
import { PublicOrderSessionService, safeCheckoutUrl, safeSaspayCheckout } from './public-order-session.service';
import { PublicOrderApi } from './public-order-api';
import { apiTestProviders } from '../../testing/api-test';
import { firstValueFrom } from 'rxjs';
import { PublicGroupingView } from '../../api/generated/models';
import { AuthService } from '../../core/auth/auth.service';
const offer = {
  offerId: 'offer',
  unitPrice: 5000,
  priceRevision: 3,
  active: true,
  currency: 'XOF',
  catalog: {
    usable: true,
    productName: 'Chemise',
    attributes: { color: 'Noir' },
    saleUnit: 'UNIT',
  },
};
const group: PublicGroupingView = {
  publicIdentifier: 'group',
  orderable: true,
  offers: [offer],
  shippingTerms: { shippingCollectionMode: 'AFTER_FINAL_ASSESSMENT' },
};
const contact: CheckoutContact = { firstName: ' Awa ', lastName: 'Diop', phone: '+221 77 123 45 67' };
describe('Panier et checkout publics', () => {
  let cart: CartStore;
  let http: HttpTestingController;
  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: apiTestProviders() });
    cart = TestBed.inject(CartStore);
    cart.select('group');
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    http.verify();
  });
  it('ajoute, incrémente, diminue et supprime à zéro', () => {
    cart.add(group, offer);
    cart.add(group, offer);
    expect(cart.count()).toBe(2);
    expect(cart.subtotal()).toBe(10000);
    cart.remove('offer');
    expect(cart.count()).toBe(1);
    cart.remove('offer');
    expect(cart.count()).toBe(0);
  });
  it('conserve les prix et révisions observés indépendamment des nouvelles offres', () => {
    cart.add(group, offer);
    cart.reconcile({ ...group, offers: [{ ...offer, unitPrice: 6500, priceRevision: 4 }] });
    expect(cart.lines()[0]).toEqual({
      offerId: 'offer',
      quantity: 1,
      expectedUnitPrice: 5000,
      expectedPriceRevision: 3,
    });
    expect(cart.ready()).toBe(false);
    cart.acceptPrice('offer');
    expect(cart.lines()[0].expectedUnitPrice).toBe(6500);
    expect(cart.lines()[0].expectedPriceRevision).toBe(4);
    expect(cart.ready()).toBe(true);
  });
  it('conserve les paniers des autres groupages', () => {
    cart.add(group, offer);
    cart.add({ ...group, publicIdentifier: 'other' }, offer);
    cart.clear('group');
    expect(cart.count()).toBe(1);
    cart.select('group');
    expect(cart.count()).toBe(0);
  });
  it('ne change pas un snapshot en ajoutant à nouveau une offre révisée', () => {
    cart.add(group, offer);
    cart.add({ ...group, offers: [{ ...offer, unitPrice: 8000 }] }, { ...offer, unitPrice: 8000 });
    expect(cart.lines()[0].expectedUnitPrice).toBe(5000);
    expect(cart.ready()).toBe(false);
  });
  it('restaure le panier dans la session', () => {
    cart.add(group, offer);
    expect(new CartStore().items()).toEqual([]);
    const next = new CartStore();
    next.select('group');
    expect(next.count()).toBe(1);
  });
  it('bloque les quantités invalides et les totaux imprécis', () => {
    cart.add(group, offer);
    cart.quantity('offer', 1.5);
    expect(cart.count()).toBe(1);
    cart.quantity('offer', -1);
    expect(cart.count()).toBe(1);
    cart.quantity('offer', 1000001);
    expect(cart.count()).toBe(1);
  });
  async function checkoutRequest(c = contact) {
    const store = TestBed.inject(CheckoutStore);
    const result = store.submit(c);
    await vi.waitFor(() =>
      expect(
        http.match('/api/public/groupings/group').map((r) => {
          r.flush(group);
          return r;
        }),
      ).toHaveLength(1),
    );
    let req: TestRequest | undefined;
    await vi.waitFor(() => {
      req = http.expectOne('/api/public/groupings/group/checkout');
    });
    if (!req) throw new Error('Checkout request missing');
    return { store, result, req };
  }
  it('réutilise la clé après erreur réseau et normalise le contenu', async () => {
    cart.add(group, offer);
    const first = await checkoutRequest();
    const key = first.req.request.headers.get('Idempotency-Key');
    first.req.error(new ProgressEvent('error'));
    expect(await first.result).toBe(false);
    const retry = await checkoutRequest({ ...contact, firstName: 'Awa', phone: '+221771234567' });
    expect(retry.req.request.headers.get('Idempotency-Key')).toBe(key);
    retry.req.flush(
      { orderNumber: 'GRP-1', trackingToken: 'private-test-token', status: 'PENDING_PAYMENT' },
      { status: 201, statusText: 'Created' },
    );
    expect(await retry.result).toBe(true);
    expect(JSON.parse(sessionStorage.getItem('groupia.public-order')!).trackingToken).toBe(
      'private-test-token',
    );
    expect(cart.count()).toBe(1);
  });
  it('réutilise la clé après timeout HTTP', async () => {
    vi.useFakeTimers();
    cart.add(group, offer);
    try {
      const first = await checkoutRequest();
      const key = first.req.request.headers.get('Idempotency-Key');
      await vi.advanceTimersByTimeAsync(20001);
      expect(await first.result).toBe(false);
      expect(first.req.cancelled).toBe(true);
      const retry = await checkoutRequest();
      expect(retry.req.request.headers.get('Idempotency-Key')).toBe(key);
      retry.req.flush({ orderNumber: 'GRP-1', replayed: true });
      await retry.result;
    } finally {
      vi.useRealTimers();
    }
  });
  it('crée une nouvelle clé si le contact change', async () => {
    cart.add(group, offer);
    const first = await checkoutRequest();
    const key = first.req.request.headers.get('Idempotency-Key');
    first.req.flush({}, { status: 500, statusText: 'Error' });
    await first.result;
    const retry = await checkoutRequest({ ...contact, firstName: 'Fatou' });
    expect(retry.req.request.headers.get('Idempotency-Key')).not.toBe(key);
    retry.req.flush({ orderNumber: 'GRP-2', replayed: true });
    await retry.result;
  });
  it('crée une nouvelle clé si le panier change', async () => {
    cart.add(group, offer);
    const first = await checkoutRequest();
    const key = first.req.request.headers.get('Idempotency-Key');
    first.req.error(new ProgressEvent('error'));
    await first.result;
    cart.quantity('offer', 2);
    const retry = await checkoutRequest();
    expect(retry.req.request.headers.get('Idempotency-Key')).not.toBe(key);
    expect(retry.req.request.body.lines[0].quantity).toBe(2);
    retry.req.flush({ orderNumber: 'GRP-2', replayed: true });
    await retry.result;
  });
  it('bloque le double-submit', async () => {
    cart.add(group, offer);
    const first = await checkoutRequest();
    expect(await first.store.submit(contact)).toBe(false);
    first.req.flush({ orderNumber: 'GRP-1', replayed: true });
    await first.result;
  });
  it('ne fabrique pas de token sur rejeu après réponse perdue', async () => {
    cart.add(group, offer);
    const r = await checkoutRequest();
    r.req.flush({ orderNumber: 'GRP-1', replayed: true });
    expect(await r.result).toBe(true);
    expect(TestBed.inject(PublicOrderSessionService).current()?.trackingToken).toBeUndefined();
  });
  it('recharge et bloque avant POST si groupage fermé', async () => {
    cart.add(group, offer);
    const pending = TestBed.inject(CheckoutStore).submit(contact);
    await vi.waitFor(() => {
      http.expectOne('/api/public/groupings/group').flush({ ...group, orderable: false });
    });
    expect(await pending).toBe(false);
    http.expectNone('/api/public/groupings/group/checkout');
    expect(cart.count()).toBe(1);
  });
  it('token transmis uniquement en header aux opérations de suivi, jamais en URL ni JWT', async () => {
    TestBed.inject(AuthService).acceptSession('jwt-test', 900);
    const p = firstValueFrom(TestBed.inject(PublicOrderApi).track('private-test-token'));
    const req = http.expectOne('/api/public/orders/track');
    expect(req.request.headers.get('X-Order-Tracking-Token')).toBe('private-test-token');
    expect(req.request.headers.has('Authorization')).toBe(false);
    expect(req.request.urlWithParams).not.toContain('private-test-token');
    req.flush({ orderNumber: 'GRP-1' });
    await p;
  });
  it('refuse les URL dangereuses ou avec identifiants', () => {
    expect(safeCheckoutUrl('javascript:alert(1)')).toBeNull();
    expect(safeCheckoutUrl('http://pay.example')).toBeNull();
    expect(safeCheckoutUrl('https://user:password@pay.example')).toBeNull();
    expect(safeCheckoutUrl('https://pay.example/checkout')).toBe('https://pay.example/checkout');
  });
  it('accepte les redirections SasPay déjà autorisées par le backend et refuse les URL non sûres', () => {
    expect(safeSaspayCheckout('https://pay.saspay.me/checkout/opaque')).toBe(
      'https://pay.saspay.me/checkout/opaque',
    );
    expect(safeSaspayCheckout('https://pay.wave.com/checkout/opaque')).toBe(
      'https://pay.wave.com/checkout/opaque',
    );
    expect(safeSaspayCheckout('https://checkout.orange-money.sn/pay/opaque')).toBe(
      'https://checkout.orange-money.sn/pay/opaque',
    );
    for (const url of [
      'http://pay.wave.com/checkout/opaque',
      'javascript:alert(1)',
      'https://user:password@pay.wave.com/pay',
      'https://pay.wave.com:8080/pay',
      'https://pay.wave.com/pay#secret',
      'https://127.0.0.1/pay',
      'https://localhost/pay',
      'https://malicious.example/checkout',
    ]) expect(safeSaspayCheckout(url)).toBeNull();
  });
});
