import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { SaspayIncidentsPage } from './saspay-incidents.page';
import { SaspayAccountingSnapshot, SaspayIncidentConsoleApi, SaspayProviderPreflight } from './saspay-incidents.api';

const accounting: SaspayAccountingSnapshot = {
  checkedAt: '2026-11-24T10:00:00Z', currency: 'XOF', organizations: [],
  knownNetWalletFlows: 15000, retainedMerchantPrincipal: 9000, merchantDeficits: 1000,
  unallocatedCollections: 1000, subscriptionRevenue: 5000, reservedPayouts: 2000,
  missingFeeProofs: 0, noteCode: 'DIAGNOSTIC_NOT_ACCOUNTING_PROOF',
};
const wallet: SaspayProviderPreflight = {
  checkedAt: '2026-11-24T10:00:30Z', status: 'READ_ONLY_ACCESS_OK', sampledTransactions: 1,
  historyHasMorePages: false, sampledBalanceRows: 1, balancesComplete: true,
  senegalXofWallets: 1, senegalAvailableXof: 8000, senegalPendingXof: 3000,
  walletFrozen: false, noteCode: 'DIAGNOSTIC_NOT_ACCOUNTING_PROOF',
};

describe('Comparaison opérateur des écritures et du portefeuille', () => {
  function page() {
    TestBed.configureTestingModule({ providers: [
      { provide: AuthService, useValue: { generation: signal(0) } },
      { provide: SaspayIncidentConsoleApi, useValue: { list: vi.fn(async () => null) } },
    ] });
    const result = TestBed.runInInjectionContext(() => new SaspayIncidentsPage());
    result.accounting.set(accounting);
    result.providerPreflight.set(wallet);
    return result;
  }

  it('conserve l’écart signé et ne double pas les réserves ni ne compense les déficits', () => {
    const result = page();
    expect(result.walletGap()).toBe(-4000);
    expect(result.potentialCoverageGap()).toBe(2000);
  });

  it('refuse une comparaison avec des soldes incomplets ou des lectures éloignées', () => {
    const result = page();
    result.providerPreflight.set({ ...wallet, balancesComplete: false });
    expect(result.walletGap()).toBeNull();
    expect(result.potentialCoverageGap()).toBeNull();
    result.providerPreflight.set({ ...wallet, checkedAt: '2026-11-24T10:02:00Z' });
    expect(result.walletGap()).toBeNull();
  });

  it('ne présente pas les fonds d’un portefeuille gelé comme mobilisables', () => {
    const result = page();
    result.providerPreflight.set({ ...wallet, walletFrozen: true });
    expect(result.walletGap()).toBe(-4000);
    expect(result.potentialCoverageGap()).toBeNull();
  });
});
