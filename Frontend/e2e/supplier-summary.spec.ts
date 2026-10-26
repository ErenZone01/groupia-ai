import { test, expect, Page } from '@playwright/test';
import { mockApi, session, shopId, groupId, noOverflow } from './api-mock';
import type { SupplierSummaryView } from '../src/app/api/generated/models';

async function supplier(page: Page) {
  await session(page);
  const base = await mockApi(page);
  const summary: SupplierSummaryView = {
    groupingCampaignId: groupId,
    acceptedOrderCount: 2,
    totalUnits: 14,
    goodsAmount: 70000,
    currency: 'XOF',
    lines: [
      {
        productNameSnapshot: 'Chemise historique',
        variantAttributesSnapshot: { color: 'noir', size: 'm' },
        totalQuantity: 7,
        goodsAmount: 35000,
        saleUnitSnapshot: 'UNIT',
        skuSnapshot: 'CHEMISE-NOIR-M',
      },
      {
        productNameSnapshot: 'Chemise historique',
        variantAttributesSnapshot: { color: 'blanc', size: 'm' },
        totalQuantity: 4,
        goodsAmount: 20000,
        saleUnitSnapshot: 'UNIT',
      },
      {
        productNameSnapshot: 'Chemise historique',
        variantAttributesSnapshot: { color: 'blanc', size: 'l' },
        totalQuantity: 3,
        goodsAmount: 15000,
        saleUnitSnapshot: 'UNIT',
      },
    ],
  };
  const state = { calls: 0, fail: false, empty: false, release: () => {}, pause: false };
  await page.route('**/supplier-summary', async (route) => {
    state.calls++;
    expect(new URL(route.request().url()).pathname).toBe(
      '/api/organizations/' + shopId + '/grouping-campaigns/' + groupId + '/supplier-summary',
    );
    expect(route.request().method()).toBe('GET');
    expect(route.request().headers()['authorization']).toBe('Bearer e2e-only-token');
    if (state.pause) await new Promise<void>((resolve) => (state.release = resolve));
    await route.fulfill({
      status: state.fail ? 500 : 200,
      contentType: 'application/json',
      body: JSON.stringify(
        state.fail
          ? { detail: 'SQL private' }
          : state.empty
            ? { ...summary, acceptedOrderCount: 0, totalUnits: 0, goodsAmount: 0, lines: [] }
            : summary,
      ),
    });
  });
  return { state, base };
}
for (const [width, theme] of [
  [360, 'light'],
  [360, 'dark'],
  [1440, 'light'],
  [1440, 'dark'],
] as const) {
  test('récapitulatif fournisseur ' + width + 'px ' + theme, async ({ page }) => {
    const { state, base } = await supplier(page);
    await page.setViewportSize({ width, height: 1000 });
    await page.emulateMedia({ colorScheme: theme });
    await page.goto('/app/groupages/' + groupId);
    expect(state.calls).toBe(0);
    // Navigation au clavier depuis l’onglet sélectionné.
    await page.getByRole('tab', { name: 'Offres', exact: true }).focus();
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('ArrowRight');
    await expect(page.getByRole('tab', { name: 'Fournisseur', exact: true })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    await expect(page.getByRole('heading', { name: 'À commander au fournisseur' })).toBeVisible();
    await expect(page.getByText('14 articles', { exact: true })).toBeVisible();
    await expect(
      page.getByRole('list', { name: 'Articles à commander' }).getByRole('listitem'),
    ).toHaveCount(3);
    await expect(page.locator('g-supplier-summary-panel')).toContainText(/70\s?000\s?F\s?CFA/);
    expect(state.calls).toBe(1);
    expect(base.calls.filter((c) => /\/orders\/[^/]+$/.test(c.path))).toHaveLength(0);
    await noOverflow(page);
    await page.screenshot({
      path: 'artifacts/supplier-summary-' + width + '-' + theme + '.png',
      fullPage: true,
    });
  });
}
test('chargement, erreur réessayable et récapitulatif vide', async ({ page }) => {
  const { state } = await supplier(page);
  state.pause = true;
  state.fail = true;
  await page.goto('/app/groupages/' + groupId + '?tab=supplier');
  await expect(
    page.getByRole('status', { name: 'Chargement du récapitulatif fournisseur' }),
  ).toBeVisible();
  await expect.poll(() => state.calls).toBe(1);
  state.release();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page.locator('g-supplier-summary-panel')).not.toContainText('SQL private');
  state.pause = false;
  state.fail = false;
  state.empty = true;
  await page.getByRole('button', { name: 'Réessayer', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: 'Aucune commande acceptée pour le moment' }),
  ).toBeVisible();
  state.empty = false;
  await page.getByRole('button', { name: 'Actualiser le récapitulatif' }).click();
  await expect(page.getByText('14 articles', { exact: true })).toBeVisible();
  expect(state.calls).toBe(3);
});
