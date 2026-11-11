import { TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { apiTestProviders } from '../../testing/api-test';
import type { SubscriptionDetails } from '../../api/generated/models';
import { AuthService } from '../../core/auth/auth.service';
import { SubscriptionStore } from './subscription.store';

const base = '/api/organizations/shop/subscription';
const details: SubscriptionDetails = {
  access: {
    organizationId: 'shop',
    status: 'TRIALING',
    trialStartedAt: '2026-10-01T00:00:00Z',
    trialEndsAt: '2026-11-01T00:00:00Z',
    canStartNewBusiness: true,
    reason: 'TRIAL_ACTIVE',
  },
  canManageSubscription: true,
  billingProviderAvailable: true,
  billingProviderSetupPending: false,
  planConfigured: true,
  billingContact: { name: 'Boutique', phoneE164: '+221771234567' },
};

describe('SubscriptionStore', () => {
  let store: SubscriptionStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [...apiTestProviders(), SubscriptionStore] });
    store = TestBed.inject(SubscriptionStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function flush(org = 'shop') {
    http.expectOne('/api/organizations/' + org + '/subscription').flush(details);
    http.expectOne('/api/organizations/' + org + '/subscription/history').flush([]);
    http.expectOne('/api/organizations/' + org + '/subscription/invoices').flush([]);
    http
      .expectOne('/api/organizations/' + org + '/subscription/invoice-preview')
      .flush({ amount: 10000, currency: 'XOF' });
  }

  const quote = {
    id: 'fee-quote',
    provider: 'SASPAY',
    network: 'wave_sn',
    networkLabel: 'Wave',
    saleAmount: 10000,
    collectionFee: 250,
    totalCharged: 10250,
    merchantNet: 10000,
    groupiaCommission: 0,
    currency: 'XOF',
    expiresAt: '2099-12-01T00:00:00Z',
  };
  async function saspay() {
    await load();
    store.details.set({ ...details, availableProviders: ['SASPAY', 'BICTORYS'] });
    store.invoices.set([invoice]);
  }
  it('SasPay hébergé initie sans devis opérateur ni deuxième session', async () => {
    await saspay();
    await store.pay(quote);
    http.expectNone((r) => r.method === 'POST');
    const action = store.pay(undefined, true);
    const request = http.expectOne(base + '/invoices/invoice/payment');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ provider: 'SASPAY' });
    expect(request.request.headers.get('Idempotency-Key'))
      .toMatch(/^[A-Za-z0-9._:-]{16,128}$/);
    request.flush({ status: 'PENDING', reconciliationRequired: false, checkoutUrl: null });
    await vi.waitFor(() => flush());
    await action;
    http.expectNone((r) => r.url.endsWith('/payment-quotes'));
  });

  it('SasPay hébergé réutilise la même clé après une réponse réseau perdue', async () => {
    await saspay();
    const first = store.pay(undefined, true);
    const request = http.expectOne(base + '/invoices/invoice/payment');
    const key = request.request.headers.get('Idempotency-Key');
    expect(request.request.body).toEqual({ provider: 'SASPAY' });
    request.flush({}, { status: 503, statusText: 'Unavailable' });
    await vi.waitFor(() => flush());
    await first;
    store.details.set({ ...details, availableProviders: ['SASPAY', 'BICTORYS'] });
    store.invoices.set([invoice]);
    await store.pay();
    http.expectNone((r) => r.url.endsWith('/payment'));
    expect(store.error()).toContain('déjà demandé');
    const retry = store.pay(undefined, true);
    const repeated = http.expectOne(base + '/invoices/invoice/payment');
    expect(repeated.request.headers.get('Idempotency-Key')).toBe(key);
    expect(repeated.request.body).toEqual(request.request.body);
    repeated.flush({ status: 'PENDING', reconciliationRequired: false, checkoutUrl: null });
    await vi.waitFor(() => flush());
    await retry;
  });

  it('SasPay hébergé n’initie rien si le fournisseur est indisponible', async () => {
    await saspay();
    store.details.set({ ...details, availableProviders: ['BICTORYS'] });
    await store.pay(undefined, true);
    http.expectNone((r) => r.method === 'POST');
  });

  it.each([true, false])(
    'récupère le checkout ou le push SasPay uniquement pour la propriétaire (%s)',
    async (checkout) => {
      const pending = store.load('shop');
      http.expectOne(base).flush({ ...details, availableProviders: ['SASPAY'] });
      http.expectOne(base + '/history').flush([]);
      http.expectOne(base + '/invoice-preview').flush({});
      http.expectOne(base + '/invoices').flush([
        {
          ...invoice,
          canPay: false,
          attemptInProgress: true,
          latestAttempt: {
            provider: 'SASPAY',
            quote: null,
            attempt: {
              status: 'PENDING',
              checkoutUrl: checkout ? 'https://pay.saspay.me/session' : null,
            },
          },
        },
      ]);
      await pending;
      expect(store.checkoutUrl()).toBe(checkout ? 'https://pay.saspay.me/session' : null);
      expect(store.phoneConfirmation()).toBe(false);
      expect(store.acceptedQuote()).toBeNull();
      const reload = store.refresh();
      http.expectOne(base).flush({ ...details, canManageSubscription: false });
      http.expectOne(base + '/history').flush([]);
      http.expectOne(base + '/invoice-preview').flush({});
      http
        .expectOne(base + '/invoices')
        .flush([{ ...invoice, canPay: false, attemptInProgress: true }]);
      await reload;
      expect(store.checkoutUrl()).toBeNull();
      expect(store.acceptedQuote()).toBeNull();
    },
  );

  it('charge les détails et l’historique une fois pour les appels concurrents', async () => {
    const first = store.load('shop');
    const second = store.load('shop');
    flush();
    await Promise.all([first, second]);
    expect(store.details()?.access?.status).toBe('TRIALING');
    expect(store.history()).toEqual([]);
  });

  it('invalide les données en cache au changement de session', async () => {
    const loaded = store.load('shop');
    flush();
    await loaded;

    TestBed.inject(AuthService).generation.update((generation) => generation + 1);
    TestBed.flushEffects();
    expect(store.details()).toBeNull();
    expect(store.history()).toEqual([]);

    const reloaded = store.load('shop');
    flush();
    await reloaded;
    expect(store.details()?.access?.status).toBe('TRIALING');
  });

  async function load() {
    const pending = store.load('shop');
    flush();
    await pending;
  }
  it('ne paie pas lorsque le provider est indisponible', async () => {
    await load();
    store.details.set({ ...details, billingProviderAvailable: false });
    await store.pay();
    http.expectNone((r) => r.method === 'POST');
  });
  it('MANAGER reste en lecture seule', async () => {
    await load();
    store.details.set({ ...details, canManageSubscription: false });
    await store.pay();
    http.expectNone((r) => r.method === 'POST');
  });
  it('annule A → B → A et conserve uniquement la dernière réponse', async () => {
    const a = store.load('shop');
    const oldA = http.match((r) => r.url.includes('/shop/'));
    const b = store.load('other');
    const oldB = http.match((r) => r.url.includes('/other/'));
    const current = store.load('shop');
    flush();
    await Promise.all([a, b, current]);
    expect(oldA.every((r) => r.cancelled)).toBe(true);
    expect(oldB.every((r) => r.cancelled)).toBe(true);
    expect(store.loading()).toBe(false);
  });
  it('force reload remplace les GET en cours', async () => {
    const first = store.load('shop');
    const old = http.match((r) => r.url.startsWith(base));
    const second = store.load('shop', true);
    flush();
    await Promise.all([first, second]);
    expect(old.every((r) => r.cancelled)).toBe(true);
  });
  it('crée sans montant puis initie uniquement avec le provider', async () => {
    await load();
    const action = store.pay();
    const create = http.expectOne(base + '/invoices');
    expect(create.request.body).toBeNull();
    expect(create.request.headers.get('Idempotency-Key')).toMatch(/^[A-Za-z0-9._:-]{16,128}$/);
    create.flush({ id: 'invoice' });
    await Promise.resolve();
    const payment = http.expectOne(base + '/invoices/invoice/payment');
    expect(payment.request.body).toEqual({ provider: 'BICTORYS' });
    payment.flush({ status: 'PENDING' });
    await vi.waitFor(() => flush());
    await action;
    expect(store.history()).toEqual([]);
  });
  it('timeout conserve la facture et la clé sans créer une nouvelle obligation', async () => {
    await load();
    store.invoices.set([invoice]);
    vi.useFakeTimers();
    try {
      const action = store.pay();
      const first = http.expectOne(base + '/invoices/invoice/payment');
      const key = first.request.headers.get('Idempotency-Key');
      await vi.advanceTimersByTimeAsync(40001);
      expect(first.cancelled).toBe(true);
      flush();
      await action;
      const retry = store.pay();
      const second = http.expectOne(base + '/invoices/invoice/payment');
      expect(second.request.headers.get('Idempotency-Key')).toBe(key);
      second.flush({ status: 'PENDING' });
      await vi.advanceTimersByTimeAsync(0);
      flush();
      await retry;
      http.expectNone((r) => r.method === 'POST' && r.url === base + '/invoices');
    } finally {
      vi.useRealTimers();
    }
  });
  it('une facture entièrement passée renouvelle seulement sur une nouvelle action', async () => {
    await load();
    store.invoices.set([invoice]);
    const initial = store.pay();
    http.expectOne(base + '/invoices/invoice/payment').flush({ status: 'FAILED' });
    await vi.waitFor(() => flush());
    await initial;
    store.invoices.set([
      { ...invoice, periodStart: '2020-01-01T00:00:00Z', periodEnd: '2020-02-01T00:00:00Z' },
    ]);
    http.expectNone((r) => r.method === 'POST');
    const renewal = store.pay();
    http.expectOne(base + '/invoices').flush({ ...invoice, id: 'next' });
    await Promise.resolve();
    http.expectOne(base + '/invoices/next/payment').flush({ status: 'PENDING' });
    await vi.waitFor(() => flush());
    await renewal;
  });
  it('changement de boutique pendant création annule le paiement', async () => {
    await load();
    const action = store.pay();
    const old = http.expectOne(base + '/invoices');
    const changed = store.load('other');
    flush('other');
    await Promise.all([action, changed]);
    expect(old.cancelled).toBe(true);
    http.expectNone((r) => r.url.endsWith('/payment'));
  });
  it.each(['payment', 'contact'] as const)(
    'une erreur %s suivie du rechargement ne fuit pas vers une autre boutique',
    async (operation) => {
      await load();
      store.invoices.set([invoice]);
      const action =
        operation === 'payment'
          ? store.pay()
          : store.saveBillingContact({ name: 'Boutique', phoneE164: '+221771234567' });
      const mutation = http.expectOne((r) => r.method !== 'GET');
      mutation.flush(
        { message: 'Erreur ancienne boutique' },
        { status: 503, statusText: 'Unavailable' },
      );
      await Promise.resolve();
      const oldReload = http.match((r) => r.method === 'GET');
      expect(oldReload).toHaveLength(4);
      const changed = store.load('other');
      flush('other');
      await Promise.all([action, changed]);
      expect(oldReload.every((r) => r.cancelled)).toBe(true);
      expect(store.error()).toBe('');
      expect(store.busy()).toBe(false);
    },
  );
  it('changement de session pendant paiement efface le checkout', async () => {
    await load();
    store.invoices.set([invoice]);
    const action = store.pay();
    const old = http.expectOne(base + '/invoices/invoice/payment');
    TestBed.inject(AuthService).generation.update((v) => v + 1);
    TestBed.flushEffects();
    await action;
    expect(old.cancelled).toBe(true);
    expect(store.checkoutUrl()).toBeNull();
    expect(store.details()).toBeNull();
  });
  it.each(['PENDING', 'REVIEW_REQUIRED', 'SETTLED'] as const)(
    'une facture %s non payable ne réinitie rien',
    async (paymentStatus) => {
      await load();
      store.invoices.set([{ ...invoice, paymentStatus, canPay: false }]);
      await store.pay();
      http.expectNone((r) => r.method === 'POST');
    },
  );
  it('une période future payée empêche un second prépaiement', async () => {
    await load();
    store.invoices.set([{ ...invoice, status: 'PAID' }]);
    await store.pay();
    http.expectNone((r) => r.method === 'POST');
  });
  it('ne fait pas confiance à une URL de checkout externe', async () => {
    await load();
    store.invoices.set([invoice]);
    const action = store.pay();
    http
      .expectOne(base + '/invoices/invoice/payment')
      .flush({ status: 'PENDING', checkoutUrl: 'https://evil.example/' });
    await vi.waitFor(() => flush());
    await action;
    expect(store.checkoutUrl()).toBeNull();
  });
  it.each(['ACTIVE', 'PAST_DUE', 'TRIALING'] as const)(
    'la lecture %s ne donne aucun droit client',
    async (status) => {
      await load();
      store.details.set({ ...details, access: { ...details.access, status } });
      expect(store.details()?.access?.status).toBe(status);
      expect(store.history()).toEqual([]);
      http.expectNone((r) => r.method === 'POST');
    },
  );
});
const invoice = {
  id: 'invoice',
  status: 'OPEN',
  periodStart: '2099-11-01T00:00:00Z',
  periodEnd: '2099-12-01T00:00:00Z',
  canPay: true,
};
