import { test, expect, Page } from '@playwright/test';
import { noOverflow } from './api-mock';
const number = 'GRP-000000000008';
const token = 'v1.opaque.test-signature';
async function mockAccess(
  page: Page,
  initial: 'NONE' | 'ORDER_ACCESS' | 'PHONE_VERIFIED' = 'NONE',
) {
  const state = {
    scope: initial,
    invalidLink: false,
    wrongOtp: false,
    networkOnce: false,
    requests: 0,
    lists: 0,
    resends: 0,
    status: 'CONFIRMED',
    canResend: true,
  };
  const order = {
    orderNumber: number,
    status: 'CONFIRMED',
    fulfillmentStatus: 'NOT_READY',
    goodsSubtotal: 15000,
    platformFeeAmount: 300,
    initialShippingChargeAmount: 0,
    initialAmountDue: 15300,
    currency: 'XOF',
    placedAt: '2026-09-20T10:00:00Z',
    items: [],
    groupingProgress: { status: 'IN_TRANSIT', departedAt: '2026-09-21T10:00:00Z' },
  };
  await page.route('**/api/public/customer-access/**', async (route) => {
    const req = route.request(),
      path = new URL(req.url()).pathname.replace('/api/public/customer-access', '');
    expect(req.headers()['authorization']).toBeUndefined();
    expect(req.headers()['referer'] ?? '').not.toContain(token);
    const reply = (json: unknown, status = 200) => route.fulfill({ status, json });
    if (req.method() === 'POST') expect(req.headers()['x-xsrf-token']).toBe('csrf-browser');
    if (path === '/csrf') return reply({ token: 'csrf-browser' });
    if (path === '/otp/request') {
      state.requests++;
      if (state.networkOnce) {
        state.networkOnce = false;
        return route.abort();
      }
      expect(req.postDataJSON().phone).toContain('77');
      return reply({
        challengeId: '00000000-0000-4000-8000-000000000001',
        message: 'Si ce numéro peut recevoir un code, celui-ci a été envoyé.',
        expiresInSeconds: 300,
        resendAfterSeconds: 60,
      });
    }
    if (path === '/otp/verify') {
      if (state.wrongOtp) return reply({ code: 'OTP_INVALID' }, 400);
      expect(req.postDataJSON().code).toBe('123456');
      state.scope = 'PHONE_VERIFIED';
      return reply({ scope: state.scope });
    }
    if (path === '/magic/exchange') {
      expect(new URL(page.url()).pathname).toBe('/s'); // Cleaned before exchange.
      if (state.invalidLink) return reply({ code: 'ORDER_ACCESS_LINK_INVALID' }, 401);
      if (state.networkOnce) {
        state.networkOnce = false;
        return route.abort();
      }
      expect(req.postDataJSON().token).toBe(token);
      state.scope = 'ORDER_ACCESS';
      return reply({ scope: state.scope, orderNumber: number });
    }
    if (path === '/session')
      return state.scope === 'NONE'
        ? reply({}, 401)
        : reply({
            scope: state.scope,
            orderNumber: state.scope === 'ORDER_ACCESS' ? number : null,
          });
    if (path === '/orders') {
      state.lists++;
      if (state.scope !== 'PHONE_VERIFIED') return reply({}, 403);
      return reply({
        items: [
          {
            ...order,
            amount: 15300,
            groupage: { title: 'Collection octobre', progress: order.groupingProgress },
          },
        ],
        page: 0,
        totalElements: 1,
        totalPages: 1,
      });
    }
    if (path === '/orders/' + number + '/capabilities')
      return reply({ canResendTrackingLink: state.scope === 'PHONE_VERIFIED' && state.canResend });
    if (path === '/orders/' + number)
      return state.scope === 'NONE' ? reply({}, 401) : reply({ ...order, status: state.status });
    if (path.endsWith('/resend-link')) {
      state.resends++;
      return route.fulfill({ status: 204 });
    }
    if (path === '/logout') {
      state.scope = 'NONE';
      return route.fulfill({ status: 204 });
    }
    return reply({}, 404);
  });
  return state;
}
test('recovery at 360px: OTP, orders, timeline and logout', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  const state = await mockAccess(page);
  await page.goto('/suivi');
  await expect(page.getByRole('heading', { name: 'Retrouver mes commandes' })).toBeVisible();
  await page.getByLabel('Numéro WhatsApp').fill('+221771234567');
  await page.getByRole('button', { name: 'Recevoir mon code' }).click();
  await expect(page.getByRole('heading', { name: 'Entrez le code reçu' })).toBeVisible();
  await expect(page.getByRole('button', { name: /Renvoyer dans/ })).toBeDisabled();
  await page.getByLabel('Code à 6 chiffres').fill('123456');
  await page.getByRole('button', { name: 'Voir mes commandes' }).click();
  await expect(page).toHaveURL(/\/mes-commandes$/);
  await expect(page.getByRole('heading', { name: 'Collection octobre' })).toBeVisible();
  await expect(page.getByText('En transport', { exact: true })).toBeVisible();
  await noOverflow(page);
  await page.getByRole('link', { name: 'Voir le suivi' }).click();
  await expect(
    page.getByRole('heading', { name: 'Votre commande, étape par étape' }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Renvoyer le lien de suivi' }).click();
  await expect(page.getByRole('status')).toContainText('renvoi');
  expect(state.resends).toBe(1);
  await page.getByLabel('Thème').selectOption('dark');
  await noOverflow(page);
  await page.screenshot({ path: 'test-results/customer-order-360-dark.png', fullPage: true });
  const storage = await page.evaluate(() =>
    JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }),
  );
  expect(storage).not.toContain('123456');
  expect(storage).not.toContain('771234567');
  await page.getByRole('button', { name: 'Fermer ma session' }).click();
  await expect(page).toHaveURL(/\/suivi$/);
});
test('wrong OTP shows a safe error and can be corrected', async ({ page }) => {
  const state = await mockAccess(page);
  state.wrongOtp = true;
  await page.goto('/suivi');
  await page.getByLabel('Numéro WhatsApp').fill('+221771234567');
  await page.getByRole('button', { name: 'Recevoir mon code' }).click();
  await page.getByLabel('Code à 6 chiffres').fill('999999');
  await page.getByRole('button', { name: 'Voir mes commandes' }).click();
  await expect(page.getByRole('alert')).toContainText('Code incorrect ou expiré');
  await expect(page.getByLabel('Code à 6 chiffres')).toHaveValue('');
  state.wrongOtp = false;
  await page.getByLabel('Code à 6 chiffres').fill('123456');
  await page.getByRole('button', { name: 'Voir mes commandes' }).click();
  await expect(page).toHaveURL(/\/mes-commandes$/);
});
test('network failure is retried only by the customer', async ({ page }) => {
  const state = await mockAccess(page);
  state.networkOnce = true;
  await page.goto('/suivi');
  await page.getByLabel('Numéro WhatsApp').fill('+221771234567');
  await page.getByRole('button', { name: 'Recevoir mon code' }).click();
  await expect(page.getByRole('alert')).toContainText('Connexion interrompue');
  expect(state.requests).toBe(1);
  await page.getByRole('button', { name: 'Recevoir mon code' }).click();
  await expect(page.getByLabel('Code à 6 chiffres')).toBeVisible();
});
test('magic access removes URL token and cannot list or resend', async ({ page }) => {
  const state = await mockAccess(page);
  await page.goto('/s/' + token);
  await expect(page).toHaveURL('/mes-commandes/' + number);
  await expect(page.getByRole('heading', { name: 'Le suivi de ma commande' })).toBeVisible();
  await expect(page.getByRole('link', { name: /Mes commandes/ })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Renvoyer le lien de suivi' })).toHaveCount(0);
  await page.goto('/mes-commandes');
  await expect(page).toHaveURL('/mes-commandes/' + number);
  expect(state.lists).toBe(0);
  expect(await page.evaluate(() => JSON.stringify(history.state))).not.toContain(token);
});
test('invalid magic is neutral, clean and mobile dark', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  const state = await mockAccess(page);
  state.invalidLink = true;
  await page.goto('/s/' + token);
  await expect(page).toHaveURL('/s');
  await expect(page.getByRole('alert')).toContainText('Ce lien est invalide');
  await page.getByLabel('Thème').selectOption('dark');
  await noOverflow(page);
  expect(await page.locator('body').innerText()).not.toContain(token);
});
test('magic network retry retains token only in memory', async ({ page }) => {
  const state = await mockAccess(page);
  state.networkOnce = true;
  await page.goto('/s/' + token);
  await expect(page.getByRole('alert')).toContainText('Connexion interrompue');
  await expect(page).toHaveURL('/s');
  await page.getByRole('button', { name: 'Réessayer' }).click();
  await expect(page).toHaveURL('/mes-commandes/' + number);
});
test('session loss never displays private order data', async ({ page }) => {
  await mockAccess(page);
  await page.goto('/mes-commandes/' + number);
  await expect(page.getByRole('alert')).toContainText('Cet accès a expiré');
  await expect(page.getByRole('heading', { name: 'Votre commande, étape par étape' })).toHaveCount(
    0,
  );
  await page.getByRole('link', { name: 'Retrouver mes commandes avec mon numéro' }).click();
  await expect(page).toHaveURL('/suivi');
});
test('foreign order number is unavailable even with phone session', async ({ page }) => {
  await mockAccess(page, 'PHONE_VERIFIED');
  await page.goto('/mes-commandes/GRP-OTHER');
  await expect(page.getByRole('alert')).toContainText('Cette commande n’est pas accessible');
  await expect(page.getByRole('heading', { name: 'Votre commande, étape par étape' })).toHaveCount(
    0,
  );
});

test('production hosting serves dotted magic links and suppresses referrers', async ({ page }) => {
  await mockAccess(page);
  const response = await page.goto('http://127.0.0.1:4300/s/' + token);
  expect(response?.status()).toBe(200);
  expect(response?.headers()['referrer-policy']).toBe('no-referrer');
  await expect(page).toHaveURL('http://127.0.0.1:4300/mes-commandes/' + number);
  await expect(
    page.getByRole('heading', { name: 'Votre commande, étape par étape' }),
  ).toBeVisible();
});
test('recovery form is keyboard accessible at 360px', async ({ page }) => {
  await mockAccess(page);
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/suivi');
  const phone = page.getByLabel('Numéro WhatsApp');
  await expect(phone).toBeVisible();
  await phone.fill('+221771234567');
  await phone.focus();
  await expect(phone).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: 'Recevoir mon code' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByLabel('Code à 6 chiffres')).toBeVisible();
  await noOverflow(page);
});

test('completed order exposes resend when authorized by backend', async ({ page }) => {
  const state = await mockAccess(page, 'PHONE_VERIFIED');
  state.status = 'COMPLETED';
  await page.goto('/mes-commandes/' + number);
  await page.getByRole('button', { name: 'Renvoyer le lien de suivi' }).click();
  await expect(page.getByRole('status')).toContainText('renvoi');
  expect(state.resends).toBe(1);
});

test('backend capability hides resend even for confirmed order', async ({ page }) => {
  const state = await mockAccess(page, 'PHONE_VERIFIED');
  state.canResend = false;
  await page.goto('/mes-commandes/' + number);
  await expect(page.getByRole('button', { name: 'Actualiser le suivi' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Renvoyer le lien de suivi' })).toHaveCount(0);
  expect(state.resends).toBe(0);
});
