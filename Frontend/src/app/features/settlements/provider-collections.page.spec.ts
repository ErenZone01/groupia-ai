import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { BoutiqueContextService } from '../../core/boutique/boutique-context.service';
import {
  ProviderCollectionsApi,
  CollectionRow,
  SimulationQuote,
  SimulationResult,
} from './provider-collections.api';
import { ProviderCollectionsPage } from './provider-collections.page';
const row: CollectionRow = {
  id: 'collection',
  paymentRequestId: 'request',
  targetType: 'DIRECT_ORDER',
  targetId: 'order',
  purpose: 'DIRECT_ORDER_INITIAL',
  provider: 'SASPAY',
  accountReference: 'abc',
  currency: 'XOF',
  sellerPrincipal: 20000,
  platformFee: 0,
  collectionFee: 500,
  customerCharged: 20500,
  confirmedAt: '2026-11-08T00:00:00Z',
  financialStatus: 'VERIFIED',
  payoutStatus: 'PROVIDER_APPROVAL_REQUIRED',
};
const quote: SimulationQuote = {
  mode: 'SIMULATION',
  enabled: true,
  kind: 'PAYOUT',
  collectionId: row.id,
  currency: 'XOF',
  recipientNet: 20000,
  fee: 200,
  simulatedCompanyFunding: 200,
  reference: 'quote',
  realOperationsEnabled: false,
};
const result: SimulationResult = {
  id: 'sim',
  collectionId: row.id,
  mode: 'SIMULATION',
  kind: 'PAYOUT',
  status: 'SUCCEEDED',
  sourceProvider: 'SASPAY',
  accountReference: 'abc',
  currency: 'XOF',
  recipientNet: 20000,
  fee: 200,
  simulatedCompanyFunding: 200,
  reference: 'SIM-test',
  createdAt: '2026-11-08T00:00:00Z',
  completedAt: '2026-11-08T00:00:01Z',
};
describe('Registre fournisseurs et simulations', () => {
  const org = signal<string | null>('org-a'),
    generation = signal(1);
  let api: {
    refresh: ReturnType<typeof vi.fn>;
    page: ReturnType<typeof vi.fn>;
    quote: ReturnType<typeof vi.fn>;
    history: ReturnType<typeof vi.fn>;
    start: ReturnType<typeof vi.fn>;
    reconcile: ReturnType<typeof vi.fn>;
  };
  beforeEach(() => {
    sessionStorage.clear();
    org.set('org-a');
    generation.set(1);
    api = {
      refresh: vi.fn(async () => ({ recorded: 0, nextCursor: null })),
      page: vi.fn(async () => ({
        items: [row],
        nextCursor: null,
        canSimulatePayout: true,
        canSimulateRefund: true,
      })),
      quote: vi.fn(async () => quote),
      history: vi.fn(async () => []),
      start: vi.fn(async () => result),
      reconcile: vi.fn(async () => result),
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: { generation, commandScope: () => `session-${generation()}` },
        },
        { provide: BoutiqueContextService, useValue: { selectedId: org } },
        { provide: ProviderCollectionsApi, useValue: api },
      ],
    });
  });
  async function page() {
    const page = TestBed.runInInjectionContext(() => new ProviderCollectionsPage());
    await vi.waitFor(() => expect(page.rows()).toHaveLength(1));
    return page;
  }
  it('demande le consentement et conserve le net sans modifier le registre', async () => {
    const p = await page();
    await p.inspect(row, 'PAYOUT');
    await p.simulate();
    expect(api.start).not.toHaveBeenCalled();
    p.confirmed.set(true);
    await p.simulate();
    expect(api.start).toHaveBeenCalledWith(
      'org-a',
      quote,
      expect.stringMatching(/^[0-9a-f-]{36}$/),
    );
    expect(p.history()[0].recipientNet).toBe(20000);
    expect(p.rows()[0].sellerPrincipal).toBe(20000);
    expect(p.confirmed()).toBe(false);
  });
  it('désactivation serveur interdit toute exécution', async () => {
    api.quote.mockResolvedValue({ ...quote, enabled: false });
    const p = await page();
    await p.inspect(row, 'PAYOUT');
    p.confirmed.set(true);
    await p.simulate();
    expect(api.start).not.toHaveBeenCalled();
  });
  it('une réponse perdue conserve la même intention après retour sur la page', async () => {
    const p = await page();
    api.start.mockRejectedValueOnce(new Error('network'));
    await p.inspect(row, 'PAYOUT');
    p.confirmed.set(true);
    await p.simulate();
    const key = api.start.mock.calls[0][2];
    expect(p.history()).toHaveLength(0);
    const reopened = await page();
    await reopened.inspect(row, 'PAYOUT');
    reopened.confirmed.set(true);
    await reopened.simulate();
    expect(api.start.mock.calls[1][2]).toBe(key);
  });
  it('un changement de session empêche une réponse tardive de révéler la simulation', async () => {
    let resolve!: (value: SimulationResult) => void;
    api.start.mockImplementation(() => new Promise<SimulationResult>((r) => (resolve = r)));
    const p = await page();
    await p.inspect(row, 'PAYOUT');
    p.confirmed.set(true);
    const pending = p.simulate();
    generation.set(2);
    org.set('org-b');
    await p.load('org-b');
    resolve(result);
    await pending;
    expect(p.history()).toHaveLength(0);
    expect(p.selected()).toBeNull();
  });
  it('suit toutes les pages du rattrapage avant la lecture', async () => {
    api.refresh.mockResolvedValueOnce({ recorded: 100, nextCursor: 'cursor' });
    await page();
    expect(api.refresh).toHaveBeenNthCalledWith(1, 'org-a', undefined);
    expect(api.refresh).toHaveBeenNthCalledWith(2, 'org-a', 'cursor');
    expect(api.page).toHaveBeenCalledTimes(1);
  });
  it('affiche les frais inconnus sans annoncer zéro', async () => {
    api.page.mockResolvedValue({
      items: [{ ...row, provider: 'BICTORYS', collectionFee: null, customerCharged: null }],
      nextCursor: null,
    });
    const fixture = TestBed.createComponent(ProviderCollectionsPage);
    fixture.detectChanges();
    await vi.waitFor(() => expect(fixture.componentInstance.rows()).toHaveLength(1));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Non documentés');
    expect(fixture.nativeElement.textContent).toContain('Non documenté');
  });
  it('revérifie une simulation en revue sans créer une nouvelle intention', async () => {
    api.history.mockResolvedValue([{ ...result, status: 'REVIEW_REQUIRED' }]);
    const p = await page();
    await p.inspect(row);
    await p.reconcile(p.history()[0]);
    expect(api.reconcile).toHaveBeenCalledWith('org-a', 'sim');
    expect(api.start).not.toHaveBeenCalled();
    expect(p.history()[0].status).toBe('SUCCEEDED');
  });
});
