import { test, expect, Page } from '@playwright/test';
import { mockApi, session, shopId, groupId, noOverflow } from './api-mock';
async function setup(
  page: Page,
  options: { enabled?: boolean; role?: string; open?: boolean; deficit?: boolean } = {},
) {
  await session(page);
  await mockApi(page, { status: options.open ? 'OPEN' : 'CLOSED' });
  const campaign = {
    campaignId: groupId,
    title: 'Groupage octobre',
    status: options.open ? 'OPEN' : 'CLOSED',
    currency: 'XOF',
    grossMerchantCollections: 25000,
    platformFees: 300,
    platformFundedRefunds: 0,
    merchantPayouts: 0,
    collectedBalance: 25000,
    eligibleBalance: 25000,
    reservedForPayout: 0,
    availableForPayout: options.open ? 0 : 25000,
    deficit: 0,
  };
  const state = {
    balance: {
      ...campaign,
      canManageSettlements: !options.role || options.role === 'OWNER',
      canManageDestination: !options.role || options.role === 'OWNER',
      providerAvailable: options.enabled ?? false,
      campaigns: [campaign],
    },
    destination: {
      id: 'destination',
      method: 'WAVE',
      holderName: 'Titulaire fixture',
      maskedPhone: '+221 ••• •• 4567',
    },
    history: [] as any[],
    posts: [] as any[],
    changes: [] as any[],
    lost: false,
    delay: 0,
  };
  if (options.deficit) {
    Object.assign(state.balance, { collectedBalance: -500, availableForPayout: 0, deficit: 500 });
    Object.assign(campaign, { collectedBalance: -500, availableForPayout: 0, deficit: 500 });
  }
  await page.route('**/api/organizations/**', async (route) => {
    const request = route.request(),
      path = new URL(request.url()).pathname;
    const reply = (json: unknown) => route.fulfill({ json });
    if (path.endsWith('/merchant-balance') || path.endsWith('/merchant-settlement'))
      return reply(state.balance);
    if (path.endsWith('/payout-destination')) {
      if (request.method() === 'PUT') {
        const body = request.postDataJSON();
        state.changes.push(body);
        expect(body.confirmed).toBe(true);
        expect(body.previousDestinationId).toBe('destination');
        state.destination = {
          ...state.destination,
          id: 'new-destination',
          holderName: body.holderName,
          method: body.method,
        };
      }
      return reply(state.destination);
    }
    if (path.endsWith('/merchant-payouts') && request.method() === 'GET')
      return reply(state.history);
    if (path.endsWith('/merchant-payouts') && request.method() === 'POST') {
      const body = request.postDataJSON();
      state.posts.push(body);
      expect(body).toEqual({ destinationId: 'destination', confirmed: true });
      expect(request.headers()['idempotency-key']).toMatch(/^[a-f0-9-]{36}$/);
      if (state.delay) await new Promise((resolve) => setTimeout(resolve, state.delay));
      state.history = [
        {
          id: 'payout',
          campaignId: groupId,
          amount: 25000,
          currency: 'XOF',
          status: state.lost ? 'REVIEW_REQUIRED' : 'PENDING',
          method: 'WAVE',
          maskedPhone: state.destination.maskedPhone,
          requestedAt: '2026-10-06T12:00:00Z',
          reconciliationRequired: state.lost,
        },
      ];
      state.balance.availableForPayout = 0;
      state.balance.reservedForPayout = 25000;
      campaign.availableForPayout = 0;
      campaign.reservedForPayout = 25000;
      if (state.lost) return route.abort();
      return reply(state.history[0]);
    }
    if (path.endsWith('/merchant-payouts/payout/reconcile')) {
      state.history[0].status = 'SUCCEEDED';
      state.history[0].reconciliationRequired = false;
      state.balance.reservedForPayout = 0;
      state.balance.merchantPayouts = 25000;
      state.balance.collectedBalance = 0;
      campaign.reservedForPayout = 0;
      campaign.merchantPayouts = 25000;
      campaign.collectedBalance = 0;
      return reply(state.history[0]);
    }
    return route.fallback();
  });
  return state;
}
for (const width of [360, 1280])
  for (const theme of ['light', 'dark'] as const) {
    test('solde, destination et clavier ' + width + ' ' + theme, async ({ page }) => {
      await page.setViewportSize({ width, height: 1000 });
      await page.emulateMedia({ colorScheme: theme });
      const state = await setup(page);
      await page.goto('/app/versements');
      await expect(
        page.getByRole('heading', { name: 'Solde boutique', exact: true }),
      ).toBeVisible();
      await expect(page.getByText(/versements sont actuellement indisponibles/)).toBeVisible();
      await expect(page.getByRole('button', { name: /Recevoir/ })).toBeDisabled();
      await expect(page.getByText(/hors montant marchand/).first()).toBeVisible();
      await page.getByRole('button', { name: 'Configurer la destination' }).click();
      await page.getByLabel('Nom du titulaire').fill('Nouvelle titulaire');
      await page.getByLabel('Numéro sénégalais').fill('771234567');
      const save = page.getByRole('button', { name: 'Enregistrer la destination' });
      await expect(save).toBeDisabled();
      await page.getByRole('checkbox').focus();
      await page.keyboard.press('Space');
      await expect(save).toBeEnabled();
      await page.keyboard.press('Tab');
      await expect(save).toBeFocused();
      await noOverflow(page);
      if (width === 360 && theme === 'dark')
        await page.screenshot({
          path: 'artifacts/merchant-settlement-mobile-dark.png',
          fullPage: true,
        });
      await save.click();
      await expect(page.getByText(/Nouvelle titulaire/)).toBeVisible();
      expect(state.changes).toHaveLength(1);
      expect(state.posts).toHaveLength(0);
    });
  }
test('manager consulte la destination sans pouvoir la modifier', async ({ page }) => {
  await setup(page, { role: 'MANAGER', enabled: true });
  await page.goto('/app/versements');
  await expect(page.getByText(/Seule la propriétaire/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Configurer la destination' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /Recevoir/ })).toHaveCount(0);
});
test('viewer ne peut pas demander de versement', async ({ page }) => {
  await setup(page, { role: 'VIEWER', enabled: true });
  await page.goto('/app/versements');
  await expect(page.getByRole('heading', { name: 'Solde boutique', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: /Recevoir/ })).toHaveCount(0);
});
test('fonds OPEN visibles mais indisponibles', async ({ page }) => {
  await setup(page, { open: true, enabled: true });
  await page.goto('/app/versements');
  await expect(page.getByText('Fonds disponibles après clôture des ventes.')).toBeVisible();
  await expect(page.getByRole('button', { name: /Recevoir/ })).toBeDisabled();
});
test('solde négatif bloque une nouvelle demande', async ({ page }) => {
  await setup(page, { deficit: true, enabled: true });
  const balanceLoaded = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname.endsWith('/merchant-balance') && response.status() === 200,
  );
  await page.goto('/app/versements');
  await balanceLoaded;
  await expect(page.getByText(/Solde à compenser/).first()).toBeVisible();
  await expect(page.getByRole('button', { name: /Recevoir/ })).toBeDisabled();
});
test('confirmation, réservation, double clic et vérification serveur', async ({ page }) => {
  const state = await setup(page, { enabled: true });
  state.delay = 400;
  await page.goto('/app/versements');
  await page.getByRole('button', { name: /Recevoir/ }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText(/4567/)).toBeVisible();
  await dialog.getByRole('button', { name: 'Confirmer le versement' }).dblclick();
  await expect(page.getByRole('heading', { name: /Versement en cours/ })).toBeVisible();
  expect(state.posts).toHaveLength(1);
  await expect(page.getByRole('button', { name: /Recevoir/ })).toBeDisabled();
  await expect(page.getByRole('button', { name: /J.ai reçu/ })).toHaveCount(0);
  await page.getByRole('button', { name: 'Vérifier le versement' }).click();
  await expect(page.getByRole('heading', { name: /Versé ·/ })).toBeVisible();
  expect(state.posts).toHaveLength(1);
});
test('réponse réseau perdue : recharge sans second payout', async ({ page }) => {
  const state = await setup(page, { enabled: true });
  state.lost = true;
  await page.goto('/app/versements');
  await page.getByRole('button', { name: /Recevoir/ }).click();
  await page.getByRole('button', { name: 'Confirmer le versement' }).click();
  await expect(page.getByRole('heading', { name: /À vérifier/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /Recevoir/ })).toBeDisabled();
  expect(state.posts).toHaveLength(1);
});
test('Fonds boutique apparaît dans le groupage fermé sans bloquer le fournisseur', async ({
  page,
}) => {
  await setup(page);
  await page.goto('/app/groupages/' + groupId);
  await expect(page.getByRole('region', { name: 'Fonds boutique' })).toBeVisible();
  await expect(
    page.getByRole('region', { name: 'Fonds boutique' }).getByText(/Collectés/),
  ).toBeVisible();
  await expect(page.getByRole('tab', { name: 'À commander' })).toBeVisible();
});
