import { test, expect, Page } from '@playwright/test';
import { mockApi, session, shopId, groupId, noOverflow } from './api-mock';
const ids = Array.from(
  { length: 25 },
  (_, i) => '90000000-0000-4000-8000-' + String(i + 1).padStart(12, '0'),
);
async function managerOrders(page: Page) {
  await session(page);
  await mockApi(page);
  const statuses = ['CONFIRMED', 'PENDING_PAYMENT', 'EXPIRED', 'CANCELLED', 'COMPLETED'];
  const records = ids.map((id, i) => ({
    id,
    campaignId: i < 20 ? groupId : 'other-group',
    orderNumber: 'GRP-' + String(i + 1).padStart(12, '0'),
    status: statuses[i % 5],
    fulfillmentStatus: 'NOT_READY',
    initialAmountDue: 10300,
    currency: 'XOF',
    placedAt: '2026-09-26T09:00:00Z',
  }));
  const state = {
    calls: [] as {
      path: string;
      status: string | null;
      page: string | null;
      campaign: string | null;
    }[],
    fail: false,
    empty: false,
  };
  await page.route('**/api/organizations/*/orders**', async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      path = url.pathname;
    expect(req.method()).toBe('GET');
    expect(req.headers()['authorization']).toBe('Bearer e2e-only-token');
    state.calls.push({
      path,
      status: url.searchParams.get('status'),
      page: url.searchParams.get('page'),
      campaign: url.searchParams.get('campaignId'),
    });
    const reply = (body: unknown, status = 200) =>
      route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (state.fail) return reply({ detail: 'SQL private' }, 500);
    if (path.endsWith('/orders')) {
      const status = url.searchParams.get('status'),
        campaign = url.searchParams.get('campaignId'),
        index = Number(url.searchParams.get('page') ?? 0),
        size = Number(url.searchParams.get('size') ?? 20);
      const filtered = state.empty
        ? []
        : records.filter(
            (o) => (!status || o.status === status) && (!campaign || o.campaignId === campaign),
          );
      return reply({
        items: filtered.slice(index * size, (index + 1) * size),
        page: index,
        size,
        totalElements: filtered.length,
        totalPages: Math.ceil(filtered.length / size),
      });
    }
    const order = records.find((o) => path.endsWith('/' + o.id));
    if (!order) return reply({}, 404);
    return reply({
      id: order.id,
      campaignId: order.campaignId,
      organizationId: shopId,
      customerContactSnapshot: {
        firstName: 'Awa',
        lastName: 'Diop',
        phoneE164: '+221771234567',
        email: 'awa@example.test',
      },
      order: {
        ...order,
        goodsSubtotal: 10000,
        platformFeeAmount: 300,
        initialShippingChargeAmount: 0,
        items: [
          {
            lineNumber: 1,
            productNameSnapshot: 'Chemise historique',
            productDescriptionSnapshot: 'Description au moment de l’achat.',
            variantAttributesSnapshot: { color: 'Blanc', size: 'M' },
            quantity: 2,
            unitPriceSnapshot: 5000,
            lineTotal: 10000,
          },
        ],
      },
    });
  });
  return state;
}
for (const [width, theme] of [
  [360, 'light'],
  [360, 'dark'],
  [768, 'light'],
  [1024, 'dark'],
  [1440, 'light'],
  [1440, 'dark'],
] as const) {
  test('commandes et détail ' + width + 'px ' + theme, async ({ page }) => {
    const state = await managerOrders(page);
    await page.setViewportSize({ width, height: 1000 });
    await page.emulateMedia({ colorScheme: theme });
    await page.goto('/app/orders');
    await expect(page.getByRole('heading', { name: 'Commandes', exact: true })).toBeVisible();
    await expect(page.getByText('25 commande(s) au total')).toBeVisible();
    await noOverflow(page);
    if (width >= 1024) await expect(page.getByRole('table')).toBeVisible();
    else await expect(page.getByRole('table')).toBeHidden();
    expect(state.calls).toHaveLength(1);
    expect(state.calls[0].page).toBe('0');
    await expect(page.locator('g-orders-panel')).not.toContainText('Awa');
    await page.screenshot({
      path: 'artifacts/manager-orders-' + width + '-' + theme + '.png',
      fullPage: true,
    });
    await page
      .getByRole('link', { name: 'Voir la commande GRP-000000000001', exact: true })
      .click();
    await expect(page.getByRole('heading', { name: 'GRP-000000000001' })).toBeVisible();
    await expect(page.getByText('Paiement confirmé', { exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Chemise historique' })).toBeVisible();
    await expect(page.getByText('Description au moment de l’achat.')).toBeVisible();
    await noOverflow(page);
    await expect(page.getByRole('button', { name: 'Marquer comme payé' })).toHaveCount(0);
    await page.screenshot({
      path: 'artifacts/manager-order-detail-' + width + '-' + theme + '.png',
      fullPage: true,
    });
    await page.getByRole('link', { name: 'Toutes les commandes' }).click();
    await expect(page).toHaveURL(/\/app\/orders(?:\?|$)/);
  });
}
test('filtres serveur et retour conservent la page', async ({ page }) => {
  const state = await managerOrders(page);
  await page.goto('/app/orders');
  await expect(page.getByText('25 commande(s) au total')).toBeVisible();
  await page.getByRole('button', { name: 'Suivante' }).click();
  await expect(page.getByText('Page 2 sur 2')).toBeVisible();
  await page.getByRole('link', { name: 'Voir la commande GRP-000000000021', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'GRP-000000000021' })).toBeVisible();
  await page.getByRole('link', { name: 'Toutes les commandes' }).click();
  await expect(page.getByText('Page 2 sur 2')).toBeVisible();
  await page.getByRole('button', { name: 'Payées', exact: true }).click();
  await expect(page.getByText('5 commande(s) pour ce filtre')).toBeVisible();
  expect(state.calls.at(-1)?.status).toBe('CONFIRMED');
  expect(state.calls.at(-1)?.page).toBe('0');
  await expect(page.locator('g-order-list')).not.toContainText('En attente');
  await expect(page.locator('g-order-list')).not.toContainText('Expirée');
  await expect(page.locator('g-order-list')).not.toContainText('Annulée');
  await expect(page.locator('g-order-list')).not.toContainText('Terminée');
});
test('recherche annoncée locale et aucun chargement automatique des autres pages', async ({
  page,
}) => {
  const state = await managerOrders(page);
  await page.goto('/app/orders');
  await expect(page.getByText('25 commande(s) au total')).toBeVisible();
  await page
    .getByRole('searchbox', { name: 'Numéro de commande sur cette page' })
    .fill('000000000025');
  await expect(page.getByRole('heading', { name: 'Aucun résultat sur cette page.' })).toBeVisible();
  expect(state.calls).toHaveLength(1);
  await page.getByRole('button', { name: 'Suivante' }).click();
  await expect(page.getByRole('link', { name: 'Voir la commande GRP-000000000025' })).toBeVisible();
});
test('accueil charge trois commandes réelles sans détails ni comptages redondants', async ({
  page,
}) => {
  const state = await managerOrders(page);
  await page.goto('/app/accueil');
  await expect(page.locator('g-recent-orders li')).toHaveCount(3);
  expect(state.calls).toHaveLength(1);
  expect(state.calls.every((c) => c.path.endsWith('/orders') && c.page === '0')).toBe(true);
  await expect(
    page.getByText('Les trois dernières commandes · montant initial à régler, frais inclus'),
  ).toBeVisible();
});

test('groupage : corrélation exacte et retour aux commandes', async ({ page }) => {
  const state = await managerOrders(page);
  await page.setViewportSize({ width: 360, height: 900 });
  await page.goto('/app/groupages/' + groupId);
  await page.getByRole('tab', { name: 'Commandes', exact: true }).click();
  await expect(page.getByText('20 commande(s) au total')).toBeVisible();
  expect(state.calls.at(-1)?.campaign).toBe(groupId);
  await expect(page.getByRole('tab', { name: 'À commander', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Payées', exact: true }).click();
  await expect(page.getByText('4 commande(s) pour ce filtre')).toBeVisible();
  expect(state.calls.at(-1)?.status).toBe('CONFIRMED');
  expect(state.calls.every((c) => c.path.endsWith('/orders'))).toBe(true);
  await noOverflow(page);
  await page.getByRole('link', { name: 'Voir la commande GRP-000000000001', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'GRP-000000000001' })).toBeVisible();
  await page.getByRole('link', { name: 'Commandes du groupage' }).click();
  await expect(page.getByRole('tab', { name: 'Commandes', exact: true })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  await expect(page.getByText('4 commande(s) pour ce filtre')).toBeVisible();
});
test('erreur réessayable et liste vide après filtre', async ({ page }) => {
  const state = await managerOrders(page);
  state.fail = true;
  await page.goto('/app/orders');
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page.locator('body')).not.toContainText('SQL private');
  state.fail = false;
  await page.getByRole('button', { name: 'Réessayer', exact: true }).click();
  await expect(page.getByText('25 commande(s) au total')).toBeVisible();
  state.empty = true;
  await page.getByRole('button', { name: 'Payées', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Aucune commande payée.' })).toBeVisible();
});
