import { test, expect, Page } from '@playwright/test';

async function fixture(
  page: Page,
  options: {
    expired?: boolean;
    unavailable?: boolean;
    push?: boolean;
    lostResponse?: boolean;
  } = {},
) {
  const state = { payments: [] as { body: unknown; key: string }[], confirmed: false };
  const receipt = {
    orderNumber: 'GRP-SAS-1',
    status: 'PENDING_PAYMENT',
    fulfillmentStatus: 'NOT_READY',
    goodsSubtotal: 5000,
    platformFeeAmount: 0,
    initialAmountDue: 5000,
    currency: 'XOF',
    paymentDeadline: new Date(Date.now() + 900000).toISOString(),
    items: [],
  };
  await page.addInitScript(
    (receipt) =>
      sessionStorage.setItem(
        'groupia.public-order',
        JSON.stringify({
          orderNumber: receipt.orderNumber,
          trackingToken: 'synthetic-capability',
          publicIdentifier: 'test-group',
          shippingLater: true,
          receipt,
        }),
      ),
    receipt,
  );
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const reply = (body: unknown, status = 200) => route.fulfill({ status, json: body });
    if (path === '/api/auth/refresh') return reply({}, 401);
    expect(request.headers()['authorization']).toBeUndefined();
    expect(request.headers()['x-order-tracking-token']).toBe('synthetic-capability');
    if (path === '/api/public/orders/track')
      return reply({ ...receipt, status: state.confirmed ? 'CONFIRMED' : 'PENDING_PAYMENT' });
    if (path.endsWith('/payment-quotes'))
      return options.unavailable
        ? reply({}, 503)
        : reply([
            {
              id: 'synthetic-quote',
              provider: 'SASPAY',
              network: 'wave_sn',
              networkLabel: 'Wave',
              saleAmount: 5000,
              groupiaCommission: 0,
              collectionFee: 125,
              totalCharged: 5125,
              merchantNet: 5000,
              currency: 'XOF',
              expiresAt: new Date(Date.now() + (options.expired ? -1000 : 120000)).toISOString(),
            },
          ]);
    if (path.endsWith('/payments')) {
      state.payments.push({
        body: request.postDataJSON(),
        key: request.headers()['idempotency-key'],
      });
      if (options.lostResponse && state.payments.length === 1) return route.abort();
      return reply({
        status: 'PENDING',
        expectedAmount: 5000,
        currency: 'XOF',
        reconciliationRequired: false,
        checkoutUrl: options.push ? null : 'https://pay.saspay.me/synthetic-checkout',
      });
    }
    return route.abort();
  });
  await page.route('https://pay.saspay.me/**', (route) =>
    route.fulfill({ body: 'Paiement simulé' }),
  );
  await page.goto('/public/order');
  await expect(page.getByRole('heading', { name: 'Paiement en attente' })).toBeVisible();
  return state;
}

test('devis explicite, aucun débit avant acceptation et confirmation uniquement serveur', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await fixture(page);
  await page
    .getByRole('button', { name: 'Voir les frais et les autres moyens de paiement' })
    .click();
  const quote = page.getByRole('region', { name: 'Devis de paiement SasPay' });
  await expect(quote).toContainText('Commission Groupia : 0 FCFA');
  await expect(quote).toContainText('Frais d’encaissement à votre charge : 125 FCFA');
  await expect(quote).toContainText('Total à payer : 5125 FCFA');
  await expect(quote).toContainText('Net marchand après encaissement : 5000 FCFA');
  expect(state.payments).toHaveLength(0);
  await quote.getByRole('button', { name: 'Accepter les frais et payer avec Wave' }).click();
  await expect(page.getByRole('link', { name: 'Payer avec Wave / Orange Money' })).toBeVisible();
  expect(state.payments).toHaveLength(1);
  expect(state.payments[0].body).toEqual({ provider: 'SASPAY', quoteId: 'synthetic-quote' });
  expect(state.payments[0].key).toMatch(/^[0-9a-f-]{36}$/);
  await expect(page.getByRole('heading', { name: 'Paiement en attente' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  state.confirmed = true;
  await page.getByRole('button', { name: 'Actualiser le suivi' }).click();
  await expect(page.getByRole('heading', { name: 'Paiement confirmé' })).toBeVisible();
});

test('devis expiré non payable', async ({ page }) => {
  const state = await fixture(page, { expired: true });
  await page
    .getByRole('button', { name: 'Voir les frais et les autres moyens de paiement' })
    .click();
  await expect(
    page.getByRole('button', { name: 'Accepter les frais et payer avec Wave' }),
  ).toBeDisabled();
  expect(state.payments).toHaveLength(0);
});

test('frais indisponibles sans bloquer le bouton Bictorys existant', async ({ page }) => {
  const state = await fixture(page, { unavailable: true });
  await page
    .getByRole('button', { name: 'Voir les frais et les autres moyens de paiement' })
    .click();
  await expect(
    page.getByText('Les frais ne peuvent pas être confirmés actuellement. Réessayez plus tard.'),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'Préparer mon paiement sécurisé' })).toBeEnabled();
  expect(state.payments).toHaveLength(0);
});

test('réponse perdue : même clé, même devis et confirmation sur téléphone sans second débit', async ({
  page,
}) => {
  const state = await fixture(page, { push: true, lostResponse: true });
  await page
    .getByRole('button', { name: 'Voir les frais et les autres moyens de paiement' })
    .click();
  await page.getByRole('button', { name: 'Accepter les frais et payer avec Wave' }).click();
  await page.getByRole('button', { name: 'Retrouver ma tentative de paiement' }).click();
  await expect(
    page.getByText('Consultez votre téléphone pour confirmer la demande de paiement.', {
      exact: false,
    }),
  ).toBeVisible();
  expect(state.payments).toHaveLength(2);
  expect(state.payments[1]).toEqual(state.payments[0]);
  await expect(page.getByRole('heading', { name: 'Paiement en attente' })).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Retrouver ma tentative de paiement' }),
  ).toHaveCount(0);
});
