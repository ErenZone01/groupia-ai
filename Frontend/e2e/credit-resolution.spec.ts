import { test, expect, Page } from '@playwright/test';
import { mockApi, session, shopId, groupId, noOverflow } from './api-mock';
import type { FulfillmentRow, RefundView } from '../src/app/api/generated/models';
const date = '2026-10-05T12:00:00Z';
async function setup(
  page: Page,
  options: {
    enabled?: boolean;
    canManage?: boolean;
    physical?: string;
    result?: string;
    network?: boolean;
  } = {},
) {
  await session(page);
  await mockApi(page, { status: 'ARRIVED' });
  const row: FulfillmentRow = {
    orderId: 'order-1',
    orderNumber: 'GRP-1',
    customerName: 'Awa Diop',
    version: 4,
    status: 'CONFIRMED',
    fulfillmentStatus: options.physical ?? 'PICKED_UP',
    pickedUpAt: date,
    shippingSettlement: {
      status: 'CREDIT_DUE',
      creditAmount: 500,
      currency: 'XOF',
      finalShippingAmount: 2500,
      initialShippingPaidAmount: 3000,
      totalShippingPaidAmount: 3000,
      netShippingPaid: 3000,
      creditStableForRefund: options.physical == null,
      refunds: [],
    },
  };
  const state = { writes: [] as Record<string, unknown>[], refunds: [] as RefundView[] };
  const resolution = () => ({
    canManage: options.canManage !== false,
    canResolve:
      options.canManage !== false && row.fulfillmentStatus === 'PICKED_UP' && !state.refunds.length,
    payoutsEnabled: !!options.enabled,
    settlement: row.shippingSettlement,
    refunds: state.refunds,
  });
  await page.route('**/grouping-campaigns/*/fulfillment', (r) =>
    r.fulfill({
      json: {
        campaign: { id: groupId, organizationId: shopId, status: 'DISTRIBUTING', version: 4 },
        canManageFulfillment: true,
        canManageRefunds: options.canManage !== false,
        counts: {
          total: 1,
          pickedUp: 1,
          customerCredits: state.refunds.some((r) => r.status === 'SUCCEEDED') ? 0 : 1,
        },
        orders: [row],
      },
    }),
  );
  await page.route('**/orders/order-1/shipping-credit', async (r) => {
    if (r.request().method() === 'GET') return r.fulfill({ json: resolution() });
    const body = r.request().postDataJSON();
    state.writes.push(body);
    const status = options.result ?? (body.method === 'MANUAL_EXTERNAL' ? 'SUCCEEDED' : 'PENDING');
    state.refunds = [
      {
        id: 'refund-1',
        method: body.method,
        externalMethod: body.externalMethod,
        amount: 500,
        currency: 'XOF',
        status,
        reconciliationRequired: status === 'REVIEW_REQUIRED',
        initiatedAt: date,
        completedAt: status === 'SUCCEEDED' ? date : undefined,
      },
    ];
    row.shippingSettlement!.refunds = state.refunds;
    if (status === 'SUCCEEDED') {
      row.status = 'COMPLETED';
      row.shippingSettlement = {
        ...row.shippingSettlement,
        status: 'SETTLED',
        creditAmount: 0,
        netShippingPaid: 2500,
        successfulShippingCreditRefunds: 500,
      };
    }
    if (options.network) return r.abort('failed');
    await r.fulfill({ json: resolution() });
  });
  await page.goto('/app/groupages/' + groupId + '?tab=distribution');
  return state;
}
for (const width of [360, 1280])
  for (const theme of ['light', 'dark']) {
    test('crédit manuel, clavier et affichage ' + width + ' ' + theme, async ({ page }) => {
      await page.setViewportSize({ width, height: 1000 });
      await page.addInitScript((t) => localStorage.setItem('groupia.theme', t), theme);
      const state = await setup(page);
      const button = page.getByRole('button', { name: /Régulariser 500/ });
      await button.focus();
      await page.keyboard.press('Enter');
      const dialog = page.getByRole('dialog', { name: 'Régulariser le crédit cliente' });
      await expect(dialog).toBeVisible();
      await expect(dialog.getByLabel('Remboursement Bictorys', { exact: true })).toBeDisabled();
      await expect(
        dialog.getByRole('button', { name: 'Confirmer la régularisation' }),
      ).toBeDisabled();
      await dialog
        .getByLabel('Moyen de restitution')
        .selectOption(width === 360 ? 'WAVE' : 'ORANGE_MONEY');
      await dialog.getByLabel(/Référence externe/).fill('REF-123456');
      const confirmed = dialog.getByRole('checkbox');
      await confirmed.focus();
      await page.keyboard.press('Space');
      await noOverflow(page);
      if (width === 360 && theme === 'dark')
        await page.screenshot({ path: 'artifacts/credit-mobile-dark.png', fullPage: true });
      await dialog.getByRole('button', { name: 'Confirmer la régularisation' }).click();
      await expect(dialog).not.toBeVisible();
      await expect(page.getByText('Régularisation déclarée par la boutique.')).toBeVisible();
      await expect(page.getByText('Transport réglé', { exact: true })).toBeVisible();
      expect(state.writes).toHaveLength(1);
      expect(state.writes[0]).not.toHaveProperty('amount');
      expect(state.writes[0]).not.toHaveProperty('recipientPhone');
      await noOverflow(page);
    });
  }
test('espèces : note et attestation obligatoires', async ({ page }) => {
  const state = await setup(page);
  await page.getByRole('button', { name: /Régulariser 500/ }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Moyen de restitution').selectOption('CASH');
  await dialog.getByRole('checkbox').check();
  await expect(dialog.getByRole('button', { name: 'Confirmer la régularisation' })).toBeDisabled();
  await dialog.getByLabel(/Note privée/).fill('Remis à la cliente au comptoir');
  await dialog.getByRole('button', { name: 'Confirmer la régularisation' }).click();
  await expect(page.getByText('Régularisation déclarée par la boutique.')).toBeVisible();
  expect(state.writes[0]).not.toHaveProperty('reference');
});
for (const status of ['PENDING', 'REVIEW_REQUIRED', 'FAILED', 'SUCCEEDED']) {
  test('payout Bictorys ' + status, async ({ page }) => {
    const state = await setup(page, { enabled: true, result: status });
    await page.getByRole('button', { name: /Régulariser 500/ }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByLabel('Remboursement Bictorys', { exact: true })).toBeChecked();
    await expect(dialog.getByLabel(/Référence externe/)).toHaveCount(0);
    await dialog.getByRole('checkbox').check();
    await dialog.getByRole('button', { name: 'Confirmer la régularisation' }).click();
    await expect(dialog).not.toBeVisible();
    const text =
      status === 'SUCCEEDED'
        ? /500.*remboursés/
        : status === 'FAILED'
          ? 'Remboursement non abouti'
          : status === 'REVIEW_REQUIRED'
            ? /Remboursement de.*à vérifier/
            : /Remboursement de.*en cours/;
    await expect(page.getByText(text).first()).toBeVisible();
    expect(state.writes).toHaveLength(1);
    await expect(page.getByRole('button', { name: /Régulariser 500/ })).toHaveCount(0);
  });
}
test('erreur réseau : recharge le résultat et interdit un second envoi', async ({ page }) => {
  const state = await setup(page, { enabled: true, result: 'REVIEW_REQUIRED', network: true });
  await page.getByRole('button', { name: /Régulariser 500/ }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('checkbox').check();
  await dialog.getByRole('button', { name: 'Confirmer la régularisation' }).click();
  await expect(dialog.getByRole('alert')).toBeVisible();
  await expect(dialog.getByText(/Remboursement de.*à vérifier/)).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Confirmer la régularisation' })).toHaveCount(0);
  expect(state.writes).toHaveLength(1);
});
test('avant retrait et opérateur : aucune sortie d’argent', async ({ page }) => {
  await setup(page, { physical: 'READY_FOR_PICKUP' });
  await expect(page.getByText('Régularisation disponible après retrait.')).toBeVisible();
  await expect(page.getByRole('button', { name: /Régulariser 500/ })).toHaveCount(0);
  await page.unrouteAll({ behavior: 'wait' });
  await setup(page, { canManage: false });
  await expect(page.getByRole('button', { name: /Régulariser 500/ })).toHaveCount(0);
  await expect(page.getByText(/Crédit cliente :/)).toBeVisible();
});
for (const method of ['MANUAL_EXTERNAL', 'BICTORYS_PAYOUT']) {
  test('suivi sécurisé : provenance ' + method, async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 900 });
    await page.route('**/api/public/customer-access/**', (r) => {
      const path = new URL(r.request().url()).pathname;
      if (path.endsWith('/session'))
        return r.fulfill({ json: { scope: 'ORDER_ACCESS', orderNumber: 'GRP-1' } });
      if (path.endsWith('/capabilities'))
        return r.fulfill({ json: { canResendTrackingLink: false } });
      return r.fulfill({
        json: {
          orderNumber: 'GRP-1',
          status: 'COMPLETED',
          fulfillmentStatus: 'PICKED_UP',
          pickedUpAt: date,
          items: [],
          groupingProgress: { status: 'DISTRIBUTING' },
          shippingSettlement: {
            status: 'SETTLED',
            finalShippingAmount: 2500,
            totalShippingPaidAmount: 3000,
            netShippingPaid: 2500,
            successfulShippingCreditRefunds: 500,
            refunds: [
              {
                method,
                status: 'SUCCEEDED',
                amount: 500,
                externalMethod: 'WAVE',
                completedAt: date,
              },
            ],
          },
        },
      });
    });
    await page.goto('/mes-commandes/GRP-1');
    await expect(page.getByText('Transport réglé', { exact: true })).toBeVisible();
    await expect(
      page.getByText(
        method === 'MANUAL_EXTERNAL'
          ? 'Régularisation déclarée par la boutique.'
          : /500.*remboursés/,
      ),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: /Régulariser/ })).toHaveCount(0);
    await noOverflow(page);
  });
}
