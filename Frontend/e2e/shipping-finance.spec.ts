import { test, expect, Page } from '@playwright/test';
import { noOverflow, mockApi, session, shopId, groupId } from './api-mock';
import type { ShippingSettlementView } from '../src/app/api/generated/models';
const number = 'GRP-000000000008';
const initial: ShippingSettlementView = {
  status: 'BALANCE_DUE',
  finalShippingAmount: 8000,
  initialShippingPaidAmount: 3000,
  additionalShippingPaidAmount: 0,
  totalShippingPaidAmount: 3000,
  amountDue: 5000,
  creditAmount: 0,
  currency: 'XOF',
  currentShippingCalculationId: 'calc-1',
  revision: 1,
  paymentInProgress: false,
  currentPaymentRequest: { status: 'OPEN', amount: 5000 },
};
async function setup(page: Page, scope = 'ORDER_ACCESS') {
  const state = { value: { ...initial }, calls: 0, reads: 0, fail: false };
  await page
    .context()
    .route('https://pay.bictorys.com/**', (r) =>
      r.fulfill({ contentType: 'text/html', body: '<h1>Paiement sandbox simulé</h1>' }),
    );
  await page.route('**/api/public/customer-access/**', async (r) => {
    const path = new URL(r.request().url()).pathname;
    const reply = (json: unknown, status = 200) => r.fulfill({ json, status });
    expect(r.request().headers()['authorization']).toBeUndefined();
    if (path.endsWith('/csrf')) return reply({ token: 'finance-csrf' });
    if (path.endsWith('/session'))
      return reply({ scope, orderNumber: scope === 'ORDER_ACCESS' ? number : undefined });
    if (path.endsWith('/capabilities'))
      return reply({ canResendTrackingLink: scope === 'PHONE_VERIFIED' });
    if (path.endsWith('/shipping-balance/payments')) {
      state.calls++;
      expect(r.request().postDataJSON()).toEqual({ provider: 'BICTORYS' });
      expect(r.request().headers()['x-xsrf-token']).toBe('finance-csrf');
      expect(r.request().headers()['idempotency-key']).toMatch(/^[a-f0-9-]{36}$/);
      if (state.fail) return reply({ code: 'PROVIDER_UNAVAILABLE' }, 503);
      state.value = { ...state.value, paymentInProgress: true };
      return reply({
        id: 'payment-1',
        status: 'PENDING',
        expectedAmount: 5000,
        currency: 'XOF',
        checkoutUrl: 'https://pay.bictorys.com/checkout',
      });
    }
    if (path.endsWith('/orders/' + number)) {
      state.reads++;
      return reply({
        orderNumber: number,
        status: 'CONFIRMED',
        fulfillmentStatus: 'NOT_READY',
        currency: 'XOF',
        items: [],
        goodsSubtotal: 1000,
        platformFeeAmount: 300,
        initialAmountDue: 4300,
        initialShippingChargeAmount: 3000,
        shippingSettlement: state.value,
        groupingProgress: { status: 'ARRIVED' },
      });
    }
    return reply({}, 404);
  });
  return state;
}
for (const width of [360, 1280])
  for (const theme of ['light', 'dark'] as const)
    for (const scope of ['ORDER_ACCESS', 'PHONE_VERIFIED']) {
      test('transport ' + scope + ' ' + width + ' ' + theme, async ({ page }) => {
        await page.setViewportSize({ width, height: 900 });
        await page.addInitScript((t) => localStorage.setItem('groupia.theme', t), theme);
        const s = await setup(page, scope);
        await page.goto('/mes-commandes/' + number + '?status=success');
        await expect(page.getByText('Reste à payer', { exact: true })).toBeVisible();
        await expect(page.getByText('Transport réglé', { exact: true })).toHaveCount(0);
        const pay = page.getByRole('button', { name: /Payer.*transport/ });
        await expect(pay).toBeVisible();
        await pay.focus();
        await expect(pay).toBeFocused();
        await noOverflow(page);
        const popup = page.context().waitForEvent('page');
        await pay.click();
        const checkout = await popup;
        await checkout.waitForLoadState();
        expect(checkout.url()).toBe('https://pay.bictorys.com/checkout');
        expect(s.calls).toBe(1);
        await expect(
          page.getByText('Paiement en cours de confirmation', { exact: true }),
        ).toBeVisible();
        await expect(pay).toHaveCount(0);
        const reads = s.reads;
        await page.evaluate(() => window.dispatchEvent(new Event('focus')));
        await expect.poll(() => s.reads).toBeGreaterThan(reads);
        await expect(page.getByText('Transport réglé', { exact: true })).toHaveCount(0);
        s.value = {
          ...s.value,
          status: 'SETTLED',
          paymentInProgress: false,
          amountDue: 0,
          totalShippingPaidAmount: 8000,
          additionalShippingPaidAmount: 5000,
          currentPaymentRequest: { status: 'CLOSED', amount: 5000 },
        };
        await page.getByRole('button', { name: 'Actualiser le suivi' }).click();
        await expect(page.getByText('Transport réglé', { exact: true })).toBeVisible();
        await expect(page.getByText('À récupérer', { exact: true })).toHaveCount(0);
        await noOverflow(page);
        if (width === 360 && theme === 'dark' && scope === 'ORDER_ACCESS')
          await page.screenshot({
            path: 'artifacts/shipping-finance-mobile-dark.png',
            fullPage: true,
          });
        await checkout.close();
      });
    }
test('crédit, non finalisé et erreur provider ne déclenchent pas un faux paiement', async ({
  page,
}) => {
  const s = await setup(page);
  s.value = {
    ...initial,
    status: 'CREDIT_DUE',
    creditAmount: 500,
    amountDue: 0,
    creditStableForRefund: false,
  };
  await page.goto('/mes-commandes/' + number);
  await expect(page.getByText(/Crédit en votre faveur/)).toBeVisible();
  await expect(page.getByText('Il sera régularisé après la remise de votre commande.')).toBeVisible();
  await expect(page.getByRole('button', { name: /Payer.*transport/ })).toHaveCount(0);
  await expect(page.getByText('Remboursement en cours')).toHaveCount(0);
  s.value = { ...s.value, creditStableForRefund: true };
  await page.getByRole('button', { name: 'Actualiser le suivi' }).click();
  await expect(page.getByText('Ce montant doit être régularisé par la boutique.')).toBeVisible();
  await expect(page.getByRole('button', { name: /Payer.*transport/ })).toHaveCount(0);
  s.value = { ...initial, status: 'NOT_FINALIZED' };
  await page.getByRole('button', { name: 'Actualiser le suivi' }).click();
  await expect(page.getByText(/Aucun paiement transport/)).toBeVisible();
  s.value = { ...initial };
  s.fail = true;
  await page.getByRole('button', { name: 'Actualiser le suivi' }).click();
  await page.getByRole('button', { name: /Payer.*transport/ }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  expect(s.calls).toBe(1);
  await expect(page.getByText('Transport réglé', { exact: true })).toHaveCount(0);
});
for (const width of [360, 1280])
  test('gérante position financière ' + width, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    await session(page);
    await mockApi(page, { status: 'SUPPLIER_ORDERED' });
    await page.route('**/grouping-campaigns/*/shipment', (r) =>
      r.fulfill({
        json: {
          campaign: { id: groupId, organizationId: shopId, status: 'SUPPLIER_ORDERED', version: 7 },
          shipment: null,
        },
      }),
    );
    await page.route('**/shipping-assessments', (r) =>
      r.fulfill({
        json: [
          {
            orderId: 'o',
            orderNumber: number,
            customerName: 'Awa Diop',
            shippingSettlement: initial,
            assessment: {
              version: 1,
              status: 'FINALIZED',
              snapshot: { terms: { shippingPricingMethod: 'FIXED' } },
              currentFinalCalculationId: 'calc-1',
              calculations: [
                {
                  id: 'calc-1',
                  revision: 1,
                  explanation: { amount: 8000, currency: 'XOF', origin: 'PROVIDER_QUOTED' },
                },
              ],
            },
          },
        ],
      }),
    );
    await page.goto('/app/groupages/' + groupId + '?tab=transport');
    await page.getByRole('button', { name: 'Gérer les frais par commande' }).click();
    await expect(page.getByText('Payé initialement', { exact: true })).toBeVisible();
    await expect(page.getByText('Compléments réglés', { exact: true })).toBeVisible();
    await expect(page.getByText('Solde ouvert', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /marquer payé/i })).toHaveCount(0);
    await noOverflow(page);
  });
