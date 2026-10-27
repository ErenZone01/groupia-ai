import { test, expect, Page } from '@playwright/test';
import { mockApi, session, shopId, groupId, noOverflow } from './api-mock';
import type { SupplierOrderResponse } from '../src/app/api/generated/models';

async function purchasing(page: Page, status = 'CLOSED') {
  await session(page);
  const api = await mockApi(page, { status });
  const line = {
    productNameSnapshot: 'Chemise commandée',
    variantAttributesSnapshot: { color: 'noir', size: 'm' },
    saleUnitSnapshot: 'UNIT',
    totalQuantity: 7,
    goodsAmount: 35000,
  };
  const live = {
    groupingCampaignId: groupId,
    acceptedOrderCount: 2,
    totalUnits: 7,
    goodsAmount: 35000,
    currency: 'XOF',
    lines: [line],
  };
  const state = {
    summaryCalls: 0,
    posts: [] as number[],
    reads: 0,
    pending: 0,
    lostResponse: false,
    failRead: false,
    conflict: false,
    pause: false,
    release: () => {},
    purchase: null as SupplierOrderResponse | null,
    late: 0,
    liveUnits: 7,
  };
  await page.route('**/supplier-summary', (route) => {
    state.summaryCalls++;
    return route.fulfill({ json: { ...live, totalUnits: state.liveUnits } });
  });
  await page.route('**/supplier-order', async (route) => {
    const req = route.request();
    expect(req.headers()['authorization']).toBe('Bearer e2e-only-token');
    expect(new URL(req.url()).pathname).toBe(
      '/api/organizations/' + shopId + '/grouping-campaigns/' + groupId + '/supplier-order',
    );
    if (req.method() === 'GET') {
      state.reads++;
      return route.fulfill({
        status: state.failRead ? 500 : state.purchase ? 200 : 404,
        json: state.failRead
          ? { detail: 'private SQL' }
          : state.purchase
            ? { ...state.purchase, lateAcceptedOrderCount: state.late }
            : { code: 'SUPPLIER_ORDER_NOT_FOUND' },
      });
    }
    expect(req.method()).toBe('POST');
    const version = req.postDataJSON().campaignVersion;
    state.posts.push(version);
    if (state.pause) await new Promise<void>((resolve) => (state.release = resolve));
    if (state.pending)
      return route.fulfill({
        status: 409,
        json: {
          code: 'SUPPLIER_ORDER_PENDING_PAYMENTS',
          details: { pendingOrderCount: state.pending },
          detail: 'email privé',
        },
      });
    if (state.conflict) {
      state.conflict = false;
      api.groups[0].version = 11;
      return route.fulfill({ status: 409, json: { code: 'GROUPING_CAMPAIGN_VERSION_CONFLICT' } });
    }
    expect(version).toBe(api.groups[0].version);
    api.groups[0].status = 'SUPPLIER_ORDERED';
    api.groups[0].version = version + 1;
    state.purchase = {
      campaign: {
        id: groupId,
        organizationId: shopId,
        status: 'SUPPLIER_ORDERED',
        version: version + 1,
      },
      supplierOrder: {
        ...live,
        id: 'supplier-order',
        organizationId: shopId,
        orderedAt: '2026-09-26T09:30:00Z',
        lines: [
          { productId: 'p', productVariantId: 'v', groupingOfferId: 'offer', snapshot: line },
        ],
      },
      lateAcceptedOrderCount: 0,
      replayed: false,
    };
    return route.fulfill({
      status: state.lostResponse ? 500 : 201,
      json: state.lostResponse ? {} : state.purchase,
    });
  });
  return state;
}
async function open(page: Page) {
  await page.goto('/app/groupages/' + groupId + '?tab=supplier');
  await expect(page.getByText('7 articles', { exact: true })).toBeVisible();
}
async function begin(page: Page) {
  await page
    .getByRole('button', { name: 'J’ai passé la commande fournisseur', exact: true })
    .click();
  await expect(
    page.getByRole('group', { name: 'Confirmation de commande fournisseur' }),
  ).toBeVisible();
}
for (const [width, theme] of [
  [360, 'light'],
  [360, 'dark'],
  [1440, 'light'],
  [1440, 'dark'],
] as const) {
  test('commande fournisseur définitive ' + width + 'px ' + theme, async ({ page }) => {
    const state = await purchasing(page);
    await page.setViewportSize({ width, height: 1000 });
    await page.emulateMedia({ colorScheme: theme });
    await open(page);
    await expect(page.getByRole('tab', { name: 'Fournisseur', exact: true })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    await begin(page);
    state.pause = true;
    const confirm = page.getByRole('button', {
      name: 'Confirmer la commande fournisseur',
      exact: true,
    });
    await confirm.click();
    await expect(confirm).toBeDisabled();
    await confirm.dispatchEvent('click');
    await expect.poll(() => state.posts.length).toBe(1);
    state.release();
    await expect(
      page.getByRole('heading', { name: 'Commande fournisseur', exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole('tab', { name: 'Fournisseur', exact: true }),
    ).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByText('Commandée au fournisseur', { exact: true })).toBeVisible();
    await expect(page.locator('g-supplier-summary-panel time')).toHaveAttribute(
      'datetime',
      '2026-09-26T09:30:00Z',
    );
    await expect(
      page.getByText(
        'Achat enregistré : ce récapitulatif ne changera plus.',
      ),
    ).toBeVisible();
    await noOverflow(page);
    await page.screenshot({
      path: 'artifacts/supplier-order-' + width + '-' + theme + '.png',
      fullPage: true,
    });
    state.liveUnits = 999;
    await page.reload();
    await expect(page.getByText('7 articles', { exact: true })).toBeVisible();
    expect(state.summaryCalls).toBe(1);
    expect(state.posts).toEqual([7]);
    await noOverflow(page);
  });
}
test('paiements en attente : nombre sûr et aucun gel', async ({ page }) => {
  const state = await purchasing(page);
  state.pending = 2;
  await open(page);
  await begin(page);
  await page
    .getByRole('button', { name: 'Confirmer la commande fournisseur', exact: true })
    .click();
  await expect(page.getByRole('alert')).toContainText(
    '2 commandes sont encore en attente de paiement.',
  );
  await expect(page.getByRole('alert')).not.toContainText('email');
  expect(state.purchase).toBeNull();
  await expect(page.getByRole('tab', { name: 'Fournisseur', exact: true })).toBeVisible();
});
test('version périmée : nouvelle version lue sur le serveur avant nouvel essai', async ({
  page,
}) => {
  const state = await purchasing(page);
  state.conflict = true;
  await open(page);
  await begin(page);
  await page
    .getByRole('button', { name: 'Confirmer la commande fournisseur', exact: true })
    .click();
  await expect(
    page.getByRole('button', { name: 'Actualiser le groupage', exact: true }),
  ).toBeEnabled();
  await expect(
    page.getByRole('button', { name: 'J’ai passé la commande fournisseur', exact: true }),
  ).toBeDisabled();
  await page.getByRole('button', { name: 'Actualiser le groupage', exact: true }).click();
  await expect(
    page.getByRole('button', { name: 'J’ai passé la commande fournisseur', exact: true }),
  ).toBeEnabled();
  await begin(page);
  await page
    .getByRole('button', { name: 'Confirmer la commande fournisseur', exact: true })
    .click();
  await expect(
    page.getByRole('heading', { name: 'Commande fournisseur', exact: true }),
  ).toBeVisible();
  expect(state.posts).toEqual([7, 11]);
});
test('réponse perdue : récupération en lecture sans deuxième création', async ({ page }) => {
  const state = await purchasing(page);
  state.lostResponse = true;
  await open(page);
  await begin(page);
  await page
    .getByRole('button', { name: 'Confirmer la commande fournisseur', exact: true })
    .click();
  await expect(
    page.getByRole('heading', { name: 'Commande fournisseur', exact: true }),
  ).toBeVisible();
  expect(state.posts).toEqual([7]);
  expect(state.reads).toBe(1);
});
test('erreur de lecture puis confirmation tardive : bon conservé et anomalie visible', async ({
  page,
}) => {
  const state = await purchasing(page);
  await open(page);
  await begin(page);
  await page
    .getByRole('button', { name: 'Confirmer la commande fournisseur', exact: true })
    .click();
  await expect(
    page.getByRole('heading', { name: 'Commande fournisseur', exact: true }),
  ).toBeVisible();
  state.failRead = true;
  await page.reload();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page.locator('g-supplier-summary-panel')).not.toContainText('SQL');
  state.failRead = false;
  state.late = 1;
  await page.getByRole('button', { name: 'Réessayer', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('confirmées après cet achat');
  await expect(page.getByText('7 articles', { exact: true })).toBeVisible();
});
test('groupage ouvert : gérer les commandes sans déclaration fournisseur', async ({ page }) => {
  await purchasing(page, 'OPEN');
  await open(page);
  await expect(
    page.getByRole('button', { name: 'J’ai passé la commande fournisseur', exact: true }),
  ).toHaveCount(0);
  await page.getByRole('button', { name: 'Gérer les commandes', exact: true }).click();
  await expect(page.getByRole('tab', { name: 'Commandes', exact: true })).toHaveAttribute(
    'aria-selected',
    'true',
  );
});
