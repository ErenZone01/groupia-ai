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
  billingProviderAvailable: false,
  billingProviderSetupPending: false,
  planConfigured: false,
  billingContact: { name: 'Boutique' },
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
  }

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

  it('ne démarre pas un paiement quand le provider ou le plan est indisponible', async () => {
    const loaded = store.load('shop');
    flush();
    await loaded;
    await store.start();
    http.expectNone(base + '/start');
  });

  it('n’envoie aucune autorité de montant au démarrage', async () => {
    const loaded = store.load('shop');
    flush();
    await loaded;
    store.details.set({
      ...details,
      billingProviderAvailable: true,
      planConfigured: true,
    });
    const started = store.start();
    const request = http.expectOne(base + '/start');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toBeNull();
    request.flush(details);
    await vi.waitFor(() => flush());
    await started;
  });
});
