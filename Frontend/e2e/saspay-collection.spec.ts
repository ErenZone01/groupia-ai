import { test, expect, Page } from '@playwright/test';

/** All PSP requests are mocked: no live charges, no external wallet interactions. */
async function fixture(
  page: Page,
  options: { lostResponse?: boolean; noCheckoutUrl?: boolean } = {},
) {
  const state = {
    requests: [] as { body: unknown; key: string }[],
    confirmed: false,
  };
  const receipt = {
    orderNumber: 'GRP-SAS-1',
    status: 'PENDING_PAYMENT',
    fulfillmentStatus: 'NOT_READY',
    goodsSubtotal: 1000,
    platformFeeAmount: 0,
    initialAmountDue: 1000,
    currency: 'XOF',
    paymentDeadline: new Date(Date.now() + 900000).toISOString(),
    items: [],
  };
  await page.addInitScript(
    (value) => sessionStorage.setItem('groupia.public-order', JSON.stringify({
      orderNumber: value.orderNumber,
      trackingToken: 'synthetic-capability',
      publicIdentifier: 'test-group',
      shippingLater: true,
      receipt: value,
    })),
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
    if (path.endsWith('/payment-quotes')) throw new Error('Obsolete network quotes must not be called');
    if (path.endsWith('/payments')) {
      state.requests.push({
        body: request.postDataJSON(),
        key: request.headers()['idempotency-key']!,
      });
      if (options.lostResponse && state.requests.length === 1) return route.abort();
      return reply({
        status: 'PENDING',
        expectedAmount: 1000,
        currency: 'XOF',
        reconciliationRequired: false,
        checkoutUrl: options.noCheckoutUrl ? null : 'https://pay.saspay.me/checkout/synthetic',
      });
    }
    return route.abort();
  });
  let groupiaOrigin = '';
  await page.route('https://pay.saspay.me/**', (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: `<!doctype html><html><body><h1>SasPay checkout simulé</h1>
        <a href="${groupiaOrigin}/paiement/retour">Retour Groupia</a></body></html>`,
    }),
  );
  await page.goto('/public/order');
  groupiaOrigin = new URL(page.url()).origin;
  await expect(page.getByRole('heading', { name: 'Paiement en attente' })).toBeVisible();
  return state;
}

test('checkout hébergé : une seule session, retour et confirmation uniquement par Groupia', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await fixture(page);
  const payer = page.getByLabel(/Adresse e-mail du payeur/);
  await payer.fill('client@example.com');
  expect(state.requests).toHaveLength(0);
  await expect(page.getByRole('button', { name: /Payer avec SasPay/ })).toBeEnabled();
  await page.getByRole('button', { name: /Payer avec SasPay/ }).click();
  await expect(page.getByRole('heading', { name: 'SasPay checkout simulé' })).toBeVisible();
  expect(state.requests).toHaveLength(1);
  expect(state.requests[0].body).toEqual({ provider: 'SASPAY', payerEmail: 'client@example.com' });
  expect(state.requests[0].key).toMatch(/^[0-9a-f-]{36}$/);
  await page.getByRole('link', { name: 'Retour Groupia' }).click();
  await expect(page.getByRole('heading', { name: 'Paiement en attente' })).toBeVisible();
  expect(state.requests).toHaveLength(1);
  state.confirmed = true;
  await page.getByRole('button', { name: 'Actualiser le suivi' }).click();
  await expect(page.getByRole('heading', { name: 'Paiement confirmé' })).toBeVisible();
  expect(state.requests).toHaveLength(1);
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
  ).toBe(true);
});

test('session créée sans URL : aucune confirmation automatique ni second POST', async ({ page }) => {
  const state = await fixture(page, { noCheckoutUrl: true });
  await page.getByRole('button', { name: /Payer avec SasPay/ }).click();
  await expect(page.getByText(/session est en cours de vérification/)).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Paiement en attente' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Paiement confirmé' })).toHaveCount(0);
  expect(state.requests).toHaveLength(1);
});

test('réponse perdue : reprendre la même intention et la même clé sans double session', async ({
  page,
}) => {
  const state = await fixture(page, { lostResponse: true, noCheckoutUrl: true });
  await page.getByRole('button', { name: /Payer avec SasPay/ }).click();
  await expect(page.getByRole('button', { name: 'Retrouver ma tentative de paiement' }))
    .toBeVisible();
  await page.getByRole('button', { name: 'Retrouver ma tentative de paiement' }).click();
  await expect.poll(() => state.requests.length).toBe(2);
  expect(state.requests[1]).toEqual(state.requests[0]);
  await expect(page.getByRole('heading', { name: 'Paiement confirmé' })).toHaveCount(0);
});
