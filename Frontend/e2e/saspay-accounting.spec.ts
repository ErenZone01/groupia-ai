import { expect, test } from '@playwright/test';
import { mockApi, noOverflow, session, shopId } from './api-mock';

test('console opérateur : rapprochement explicite, fonds séparés et aucun POST financier', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await session(page);
  await mockApi(page);
  let reads = 0;
  await page.route('**/api/platform/saspay/incidents**', route => {
    expect(route.request().method()).toBe('GET');
    const path = new URL(route.request().url()).pathname;
    const reply = (body: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
    if (path.endsWith('/access')) return reply({ allowed: true });
    if (path.endsWith('/accounting')) {
      reads++;
      return reply({
        checkedAt: '2026-11-24T10:00:00Z', currency: 'XOF', knownNetWalletFlows: 15000,
        retainedMerchantPrincipal: 9000, merchantDeficits: 1000, unallocatedCollections: 1000,
        subscriptionRevenue: 5000, reservedPayouts: 2000, missingFeeProofs: 0,
        noteCode: 'DIAGNOSTIC_NOT_ACCOUNTING_PROOF', organizations: [{
          organizationId: shopId, collectedPrincipal: 15000, merchantPrincipal: 9000,
          subscriptionRevenue: 5000, unallocatedCollections: 1000, legacyPlatformFees: 0,
          collectionFees: 37, paidPayouts: 0, reservedPayouts: 2000, payoutFees: 0,
          additionalWalletFees: 0, refundsDue: 0, externallyDeclaredRefunds: 0,
          mixedAccountRefundCases: 0, missingFeeProofs: 0,
        }],
      });
    }
    if (path.endsWith('/provider-preflight')) return reply({
      checkedAt: '2026-11-24T10:00:30Z', status: 'READ_ONLY_ACCESS_OK', sampledTransactions: 1,
      historyHasMorePages: false, sampledBalanceRows: 1, balancesComplete: true,
      senegalXofWallets: 1, senegalAvailableXof: 8000, senegalPendingXof: 3000, walletFrozen: false,
      noteCode: 'DIAGNOSTIC_NOT_ACCOUNTING_PROOF',
    });
    return reply({ items: [], total: 0, page: 0, size: 20, open: 0, inReview: 0, closed: 0, checkpoint: null });
  });
  await page.goto('/app/admin/incidents-saspay');
  await expect(page.getByRole('heading', { name: 'Fonds enregistrés par boutique' })).toBeVisible();
  expect(reads).toBe(0);
  await page.getByRole('button', { name: 'Lire les écritures Groupia' }).click();
  await expect(page.getByText(shopId, { exact: true })).toBeVisible();
  await expect(page.getByText('Abonnements réglés et affectés à Groupia', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Tester la lecture SasPay' }).click();
  await expect(page.getByText(/Écart non expliqué/)).toContainText(/-4\s?000/);
  await expect(page.getByText(/Insuffisance potentielle/)).toContainText(/2\s?000/);
  await expect(page.getByText(/Un écart nul ne prouve pas la solvabilité/)).toBeVisible();
  expect(reads).toBe(1);
  await noOverflow(page);
});
