import { test, expect, Page } from '@playwright/test';
import { mockApi, session, shopId, groupId, noOverflow } from './api-mock';
import type { FulfillmentRow, FulfillmentBatch } from '../src/app/api/generated/models';
const time = '2026-10-04T12:00:00Z';
function order(
  id: string,
  status: 'SETTLED' | 'CREDIT_DUE' | 'BALANCE_DUE' | 'NOT_FINALIZED',
): FulfillmentRow {
  return {
    orderId: id,
    orderNumber: 'GRP-' + id,
    customerName: id === '1' ? 'Awa Diop' : 'Éva Ndiaye',
    version: 1,
    status: 'CONFIRMED',
    fulfillmentStatus: 'NOT_READY',
    canMarkReady: ['SETTLED', 'CREDIT_DUE'].includes(status),
    canPickUp: false,
    canRevoke: false,
    history: [],
    readinessBlocker:
      status === 'BALANCE_DUE'
        ? 'FULFILLMENT_SHIPPING_BALANCE_DUE'
        : status === 'NOT_FINALIZED'
          ? 'FULFILLMENT_SHIPPING_NOT_FINALIZED'
          : undefined,
    shippingSettlement: {
      status,
      currency: 'XOF',
      finalShippingAmount:
        status === 'NOT_FINALIZED'
          ? undefined
          : status === 'CREDIT_DUE'
            ? 2500
            : status === 'BALANCE_DUE'
              ? 8000
              : 3000,
      initialShippingPaidAmount: 3000,
      totalShippingPaidAmount: 3000,
      amountDue: status === 'BALANCE_DUE' ? 5000 : 0,
      creditAmount: status === 'CREDIT_DUE' ? 500 : 0,
      paymentInProgress: false,
    },
  };
}
async function setup(page: Page, viewer = false) {
  await session(page);
  const app = await mockApi(page, { status: 'ARRIVED' });
  const state = {
    rows: [
      order('1', 'SETTLED'),
      order('2', 'CREDIT_DUE'),
      order('3', 'BALANCE_DUE'),
      order('4', 'NOT_FINALIZED'),
    ],
    writes: 0,
    conflict: false,
  };
  const value = (): FulfillmentBatch => ({
    campaign: {
      id: groupId,
      organizationId: shopId,
      status: app.groups[0].status,
      version: app.groups[0].version,
    },
    canManageFulfillment: !viewer,
    counts: {
      total: 4,
      toPrepare: state.rows.filter((r) => r.fulfillmentStatus === 'NOT_READY').length,
      ready: state.rows.filter((r) => r.fulfillmentStatus === 'READY_FOR_PICKUP').length,
      pickedUp: state.rows.filter((r) => r.fulfillmentStatus === 'PICKED_UP').length,
      paymentBlocked: 1,
      customerCredits: 1,
      eligible: state.rows.filter((r) => r.fulfillmentStatus === 'NOT_READY' && !r.readinessBlocker)
        .length,
    },
    orders: state.rows.map((r) =>
      viewer ? { ...r, canMarkReady: false, canPickUp: false, canRevoke: false } : r,
    ),
  });
  await page.route('**/grouping-campaigns/*/fulfillment', (r) => r.fulfill({ json: value() }));
  await page.route(
    /\/grouping-campaigns\/[^/]+\/orders\/[^/]+\/(ready-for-pickup|picked-up|revoke-readiness)$/,
    async (r) => {
      state.writes++;
      const body = r.request().postDataJSON();
      const path = new URL(r.request().url()).pathname;
      const row = state.rows.find((o) => path.includes('/orders/' + o.orderId + '/'))!;
      expect(r.request().headers()['authorization']).toContain('Bearer');
      expect(body.version).toBe(row.version);
      if (state.conflict) {
        state.conflict = false;
        row.version!++;
        return r.fulfill({ status: 409, json: { code: 'ORDER_VERSION_CONFLICT' } });
      }
      row.version!++;
      const revoked = path.endsWith('/revoke-readiness');
      if (revoked) {
        expect(body).toEqual({ version: row.version! - 1, reason: 'Article manquant' });
        row.fulfillmentStatus = 'NOT_READY';
        row.readyForPickupAt = undefined;
        row.canMarkReady = true;
        row.canPickUp = false;
        row.canRevoke = false;
      } else {
        expect(body).toEqual({ version: row.version! - 1, confirmed: true });
        const pickup = path.endsWith('/picked-up');
        row.fulfillmentStatus = pickup ? 'PICKED_UP' : 'READY_FOR_PICKUP';
        row.canMarkReady = false;
        row.canPickUp = !pickup;
        row.canRevoke = !pickup;
        if (pickup) {
          row.pickedUpAt = time;
          if (row.shippingSettlement?.status === 'SETTLED') row.status = 'COMPLETED';
        } else {
          row.readyForPickupAt = time;
          app.groups[0].status = 'DISTRIBUTING';
        }
      }
      row.history!.push({
        id: String(state.writes),
        type: revoked ? 'READINESS_REVOKED' : row.fulfillmentStatus,
        recordedAt: time,
        note: revoked ? body.reason : undefined,
      });
      await r.fulfill({ json: value() });
    },
  );
  await page.goto('/app/groupages/' + groupId + '?tab=distribution');
  return state;
}
for (const width of [360, 1280])
  for (const theme of ['light', 'dark']) {
    test('distribution opérateur ' + width + ' ' + theme, async ({ page }) => {
      await page.setViewportSize({ width, height: 1000 });
      await page.addInitScript((t) => localStorage.setItem('groupia.theme', t), theme);
      const state = await setup(page);
      await expect(page.getByRole('heading', { name: 'Distribution et retraits' })).toBeVisible();
      await expect(page.getByText('2 commande(s) éligible(s)')).toBeVisible();
      const first = page.getByRole('article', { name: 'Distribution GRP-1' });
      const credit = page.getByRole('article', { name: 'Distribution GRP-2' });
      await expect(
        page
          .getByRole('article', { name: 'Distribution GRP-3' })
          .getByText('En attente de paiement', { exact: true }),
      ).toBeVisible();
      await expect(
        page
          .getByRole('article', { name: 'Distribution GRP-4' })
          .getByText('Transport à finaliser', { exact: true }),
      ).toBeVisible();
      const search = page.getByLabel('Rechercher une commande ou une cliente');
      await search.fill('awa');
      await expect(credit).toHaveCount(0);
      await search.fill('');
      await page.getByLabel('Afficher', { exact: true }).selectOption('credit');
      await expect(first).toHaveCount(0);
      await expect(credit).toBeVisible();
      await page.getByLabel('Afficher', { exact: true }).selectOption('all');
      await first.getByRole('button', { name: 'Marquer prête' }).focus();
      await page.keyboard.press('Enter');
      const ready = first.getByRole('button', { name: 'Confirmer la disponibilité' });
      await expect(ready).toBeDisabled();
      const checkbox = first.getByRole('checkbox');
      await checkbox.focus();
      await page.keyboard.press('Space');
      await ready.click();
      await expect(
        first.getByRole('button', { name: 'Confirmer le retrait', exact: true }),
      ).toBeVisible();
      expect(state.writes).toBe(1);
      await first
        .getByRole('button', { name: 'Retirer la mise à disposition', exact: true })
        .click();
      const revoke = first.getByRole('button', {
        name: 'Confirmer le retrait de la disponibilité',
      });
      await expect(revoke).toBeDisabled();
      await first.getByLabel('Motif obligatoire').fill('Article manquant');
      await revoke.click();
      await expect(first.getByRole('button', { name: 'Marquer prête' })).toBeVisible();
      await first.getByRole('button', { name: 'Marquer prête' }).click();
      await first.getByRole('checkbox').check();
      await first.getByRole('button', { name: 'Confirmer la disponibilité' }).click();
      await first.getByRole('button', { name: 'Confirmer le retrait', exact: true }).click();
      await first.getByRole('checkbox').check();
      await first.getByRole('button', { name: 'Confirmer la remise de la commande' }).click();
      await expect(first.getByText('Commande récupérée', { exact: true })).toBeVisible();
      await expect(first.getByText(/Retirée le/)).toBeVisible();
      expect(state.rows[0].status).toBe('COMPLETED');
      await credit.getByRole('button', { name: 'Marquer prête' }).click();
      await credit.getByRole('checkbox').check();
      await credit.getByRole('button', { name: 'Confirmer la disponibilité' }).click();
      await credit.getByRole('button', { name: 'Confirmer le retrait', exact: true }).click();
      await credit.getByRole('checkbox').check();
      await credit.getByRole('button', { name: 'Confirmer la remise de la commande' }).click();
      await expect(credit.getByText('Commande récupérée', { exact: true })).toBeVisible();
      expect(state.rows[1].status).toBe('CONFIRMED');
      await expect(credit.getByText(/Ce crédit n’empêche pas le retrait/)).toBeVisible();
      await noOverflow(page);
      if (width === 360 && theme === 'dark')
        await page.screenshot({ path: 'artifacts/distribution-mobile-dark.png', fullPage: true });
    });
  }
test('distribution : conflit de version recharge et viewer reste en lecture seule', async ({
  page,
}) => {
  const state = await setup(page);
  state.conflict = true;
  const first = page.getByRole('article', { name: 'Distribution GRP-1' });
  await first.getByRole('button', { name: 'Marquer prête' }).click();
  await first.getByRole('checkbox').check();
  await first.getByRole('button', { name: 'Confirmer la disponibilité' }).click();
  await expect(page.getByRole('alert')).toContainText('autre personne');
  await expect(first.getByRole('button', { name: 'Marquer prête' })).toBeVisible();
  expect(state.writes).toBe(1);
  await page.unrouteAll({ behavior: 'wait' });
  await setup(page, true);
  await expect(page.getByText(/lecture seule à la distribution/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Marquer prête' })).toHaveCount(0);
});
for (const physical of ['NOT_READY', 'READY_FOR_PICKUP', 'PICKED_UP']) {
  test('suivi cliente distribution ' + physical, async ({ page }) => {
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
          status: 'CONFIRMED',
          fulfillmentStatus: physical,
          readyForPickupAt: physical === 'NOT_READY' ? undefined : time,
          pickedUpAt: physical === 'PICKED_UP' ? time : undefined,
          items: [],
          shippingSettlement: { status: 'CREDIT_DUE', creditAmount: 500, currency: 'XOF' },
          groupingProgress: { status: 'DISTRIBUTING' },
        },
      });
    });
    await page.goto('/mes-commandes/GRP-1');
    await expect(page.getByText(/Crédit en votre faveur :/)).toBeVisible();
    const current = page.locator('[aria-current=step]');
    await expect(current).toContainText(
      physical === 'PICKED_UP'
        ? 'Commande récupérée'
        : physical === 'READY_FOR_PICKUP'
          ? 'Prête à être récupérée'
          : 'Arrivée',
    );
    if (physical !== 'NOT_READY') await expect(current).toContainText('2026');
    await expect(page.getByRole('button', { name: 'Marquer prête' })).toHaveCount(0);
    await noOverflow(page);
  });
}
