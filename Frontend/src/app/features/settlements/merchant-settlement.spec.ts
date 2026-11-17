import { TestBed } from '@angular/core/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { apiTestProviders } from '../../testing/api-test';
import { MerchantSettlementStore } from './merchant-settlement.store';
import { MerchantBalanceSummary } from './merchant-balance-summary';
import { PayoutDestinationEditor } from './payout-destination-editor';
import type { MerchantBalance, MerchantPayoutView } from '../../api/generated/models';
import { payoutLabel } from './settlements.page';
import { AuthService } from '../../core/auth/auth.service';
const base = '/api/organizations/shop';
const balance: MerchantBalance = {
  grossMerchantCollections: 25000,
  platformFees: 300,
  collectedBalance: 25000,
  availableForPayout: 25000,
  reservedForPayout: 0,
  currency: 'XOF',
  providerAvailable: true,
  canManageSettlements: true,
  canManageDestination: true,
  campaigns: [{ campaignId: 'campaign', status: 'CLOSED', availableForPayout: 25000 }],
};
const destination = {
  id: 'destination',
  method: 'WAVE',
  holderName: 'Titulaire',
  maskedPhone: '+221 ••• •• 4567',
};
describe('Merchant settlement : requêtes financières', () => {
  let store: MerchantSettlementStore, http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [...apiTestProviders(), MerchantSettlementStore] });
    store = TestBed.inject(MerchantSettlementStore);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());
  function flush(value = balance, org = 'shop', history: MerchantPayoutView[] = []) {
    http.expectOne('/api/organizations/' + org + '/merchant-balance').flush(value);
    http.expectOne('/api/organizations/' + org + '/payout-destination').flush(destination);
    http.expectOne('/api/organizations/' + org + '/merchant-payouts').flush(history);
  }
  async function load(value = balance) {
    const p = store.load('shop');
    flush(value);
    await p;
  }
  it('conserve les montants serveur sans ajouter les frais au disponible', async () => {
    await load();
    expect(store.balance()?.availableForPayout).toBe(25000);
    expect(store.balance()?.platformFees).toBe(300);
  });
  it.each(['disabled', 'permission', 'zero'])(
    'ne demande pas de versement si %s',
    async (state) => {
      await load({
        ...balance,
        providerAvailable: state !== 'disabled',
        canManageSettlements: state !== 'permission',
        campaigns: [{ campaignId: 'campaign', availableForPayout: state === 'zero' ? 0 : 25000 }],
      });
      await store.request('campaign');
      http.expectNone(base + '/grouping-campaigns/campaign/merchant-payouts');
    },
  );
  it('ne transmet ni montant ni statut ni bénéficiaire et protège le double clic', async () => {
    await load();
    const p = store.request('campaign');
    await store.request('campaign');
    const r = http.expectOne(base + '/grouping-campaigns/campaign/merchant-payouts');
    expect(r.request.body).toEqual({ destinationId: 'destination', confirmed: true });
    expect(r.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    r.flush({ status: 'PENDING' });
    await vi.waitFor(() => flush({ ...balance, availableForPayout: 0, reservedForPayout: 25000 }));
    await p;
    expect(store.balance()?.reservedForPayout).toBe(25000);
  });
  it('affiche un refus explicite et autorise une nouvelle demande avec une autre clé', async () => {
    await load();
    const first = store.request('campaign');
    const r1 = http.expectOne(base + '/grouping-campaigns/campaign/merchant-payouts');
    const firstKey = r1.request.headers.get('Idempotency-Key');
    r1.flush({ id: 'rejected-1', status: 'FAILED' });
    await vi.waitFor(() => flush());
    await first;
    expect(store.error()).toContain('Retrait refusé');

    const second = store.request('campaign');
    const r2 = http.expectOne(base + '/grouping-campaigns/campaign/merchant-payouts');
    expect(r2.request.headers.get('Idempotency-Key')).not.toEqual(firstKey);
    r2.flush({ id: 'pending-2', status: 'PENDING' });
    await vi.waitFor(() => flush({ ...balance, availableForPayout: 0, reservedForPayout: 25000 }));
    await second;
    expect(store.error()).toContain('aucun versement confirmé');
  });

  it('conserve la clé de reprise après une erreur réseau sans réessai automatique', async () => {
    await load();
    const p = store.request('campaign');
    const r = http.expectOne(base + '/grouping-campaigns/campaign/merchant-payouts'),
      key = r.request.headers.get('Idempotency-Key');
    r.error(new ProgressEvent('error'));
    await vi.waitFor(() => flush());
    await p;
    http.expectNone(base + '/grouping-campaigns/campaign/merchant-payouts');
    await store.request('campaign');
    http.expectNone(base + '/grouping-campaigns/campaign/merchant-payouts');
    expect(store.error()).toContain('Ne relancez pas');
    expect(key).toBeTruthy();
  });
  it('attend la clôture serveur NON_EXECUTE puis crée une nouvelle clé pour la vente directe', async () => {
    const direct = {
      ...balance,
      availableForPayout: 1000,
      directOrders: [{ directOrderId: 'direct', availableForPayout: 1000 }],
    };
    const path = base + '/direct-orders/direct/merchant-payouts';
    const historical = { id: 'old', directOrderId: 'direct', status: 'REVIEW_REQUIRED' };
    await load(direct);
    const pending = store.requestDirect('direct');
    const first = http.expectOne(path);
    const originalKey = first.request.headers.get('Idempotency-Key');
    first.error(new ProgressEvent('error'));
    await vi.waitFor(() => flush(direct, 'shop', [historical]));
    await pending;
    await store.requestDirect('direct');
    http.expectNone(path);
    const refresh = store.load('shop');
    flush(direct, 'shop', [{ ...historical, status: 'NON_EXECUTE' }]);
    await refresh;
    http.expectNone(path);
    const next = store.requestDirect('direct');
    const request = http.expectOne(path);
    expect(request.request.headers.get('Idempotency-Key')).not.toBe(originalKey);
    request.flush({ id: 'new', status: 'PENDING' });
    await vi.waitFor(() => flush(direct));
    await next;
  });
  it('ne permet pas au manager de modifier une destination', async () => {
    await load({ ...balance, canManageDestination: false });
    await store.saveDestination('WAVE', '771234567', 'A', true);
    http.expectNone(base + '/payout-destination');
  });
  it('envoie une révision explicite et attend une confirmation', async () => {
    await load();
    await store.saveDestination('WAVE', '771234567', 'A', false);
    http.expectNone(base + '/payout-destination');
    const p = store.saveDestination('WAVE', '771234567', 'A', true);
    const r = http.expectOne(base + '/payout-destination');
    expect(r.request.method).toBe('PUT');
    expect(r.request.body.previousDestinationId).toBe('destination');
    r.flush(destination);
    await vi.waitFor(() => flush());
    await p;
  });
  it('ignore les réponses retardées après changement de boutique', async () => {
    const a = store.load('shop'),
      b = store.load('other');
    flush({ ...balance, grossMerchantCollections: 100 }, 'other');
    await b;
    flush(balance);
    await a;
    expect(store.balance()?.grossMerchantCollections).toBe(100);
  });
  it('ignore une mutation ancienne après A vers B vers A', async () => {
    await load();
    const mutation = store.request('campaign');
    const old = http.expectOne(base + '/grouping-campaigns/campaign/merchant-payouts');
    const other = store.load('other');
    flush({ ...balance, availableForPayout: 100 }, 'other');
    await other;
    await load({ ...balance, availableForPayout: 700 });
    old.flush({ status: 'PENDING' });
    await mutation;
    expect(store.balance()?.availableForPayout).toBe(700);
    expect(store.busy()).toBe(false);
    http.expectNone(base + '/merchant-balance');
  });
  it('efface les données lorsque la boutique disparaît et ignore la lecture en vol', async () => {
    const pending = store.load('shop');
    await store.load('');
    flush();
    await pending;
    expect(store.balance()).toBeNull();
    expect(store.destination()).toBeNull();
    expect(store.history()).toEqual([]);
    expect(store.loading()).toBe(false);
    await store.request('campaign');
    http.expectNone(base + '/grouping-campaigns/campaign/merchant-payouts');
  });
  it('ignore une mutation après expiration de session et interdit une nouvelle demande', async () => {
    await load();
    const pending = store.request('campaign');
    const old = http.expectOne(base + '/grouping-campaigns/campaign/merchant-payouts');
    TestBed.inject(AuthService).clear();
    TestBed.tick();
    old.flush({ status: 'PENDING' });
    await pending;
    expect(store.balance()).toBeNull();
    expect(store.history()).toEqual([]);
    expect(store.busy()).toBe(false);
    await store.request('campaign');
    http.expectNone(base + '/grouping-campaigns/campaign/merchant-payouts');
    http.expectNone(base + '/merchant-balance');
  });
  it('observe la session même si le store est créé avant la sélection de boutique', async () => {
    TestBed.tick();
    await load();
    TestBed.inject(AuthService).clear();
    TestBed.tick();
    expect(store.balance()).toBeNull();
    expect(store.destination()).toBeNull();
    expect(store.history()).toEqual([]);
  });
});
describe('Présentation et confirmation', () => {
  it('affiche le solde négatif et les frais distincts', () => {
    const f = TestBed.createComponent(MerchantBalanceSummary);
    f.componentRef.setInput('value', { ...balance, deficit: 500, availableForPayout: 0 });
    f.detectChanges();
    expect(f.nativeElement.textContent).toContain('Solde à compenser');
    expect(f.nativeElement.textContent).toContain('hors montant marchand');
    f.destroy();
  });
  it('exige le titulaire, le numéro et une attestation', () => {
    const f = TestBed.createComponent(PayoutDestinationEditor),
      c = f.componentInstance,
      emit = vi.spyOn(c.save, 'emit');
    c.submit();
    expect(emit).not.toHaveBeenCalled();
    c.phone = '771234567';
    c.holder = 'Titulaire';
    c.submit();
    expect(emit).not.toHaveBeenCalled();
    c.confirmed = true;
    c.submit();
    expect(emit).toHaveBeenCalledOnce();
    expect(c.confirmed).toBe(false);
    f.destroy();
  });
  it.each(['PENDING', 'REVIEW_REQUIRED', 'SUCCEEDED', 'FAILED', 'NON_EXECUTE'])(
    'traduit le statut %s',
    (status) => {
      expect(payoutLabel(status)).not.toBe(status);
    },
  );
});
