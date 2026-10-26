import { test, expect, Page } from '@playwright/test';
import { mockApi, session, groupId, noOverflow } from './api-mock';
async function setup(page: Page, conflict = false) {
  await session(page);
  const app = await mockApi(page, { status: 'DISTRIBUTING' });
  let blocked = false;
  let writes = 0;
  await page.route('**/grouping-campaigns/*/completion', (route) =>
    route.fulfill({
      json: {
        eligible: !blocked,
        canComplete: true,
        campaignStatus: app.groups[0].status,
        campaignVersion: app.groups[0].version,
        totalOrders: 1,
        completedOrders: 1,
        completedAt: app.groups[0].completedAt,
        blockers: blocked
          ? [{ code: 'PAYMENT_REVIEW_OPEN', orderId: 'order', orderNumber: 'GRP-1' }]
          : [],
      },
    }),
  );
  await page.route('**/grouping-campaigns/*/complete', async (route) => {
    writes++;
    expect(route.request().postDataJSON()).toEqual({ version: 7, confirmed: true });
    if (conflict) {
      blocked = true;
      return route.fulfill({ status: 409, json: { code: 'GROUPING_COMPLETION_BLOCKED' } });
    }
    app.groups[0] = {
      ...app.groups[0],
      status: 'COMPLETED',
      version: 8,
      completedAt: '2026-10-12T12:00:00Z',
    };
    return route.fulfill({
      json: {
        eligible: false,
        canComplete: false,
        campaignStatus: 'COMPLETED',
        campaignVersion: 8,
        completedAt: app.groups[0].completedAt,
        totalOrders: 1,
        blockers: [],
      },
    });
  });
  await page.goto('/app/groupages/' + groupId + '?tab=completion');
  return () => writes;
}
for (const width of [360, 1280])
  test('confirmation explicite et fin définitive à ' + width + 'px', async ({ page }) => {
    await page.setViewportSize({ width, height: 850 });
    const writes = await setup(page);
    await page
      .getByRole('button', { name: 'Terminer définitivement le groupage', exact: true })
      .click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.getByRole('dialog')).toContainText('Aucune réouverture');
    await page.getByRole('button', { name: 'Annuler', exact: true }).click();
    expect(writes()).toBe(0);
    await page
      .getByRole('button', { name: 'Terminer définitivement le groupage', exact: true })
      .click();
    await page.getByRole('button', { name: 'Confirmer la fin du groupage', exact: true }).click();
    await expect(page.getByText('Groupage terminé.', { exact: false })).toBeVisible();
    expect(writes()).toBe(1);
    await expect(
      page.getByRole('button', { name: 'Terminer définitivement le groupage', exact: true }),
    ).toHaveCount(0);
    await noOverflow(page);
  });
test('un conflit actualise les blocages avant une nouvelle confirmation', async ({ page }) => {
  const writes = await setup(page, true);
  await page
    .getByRole('button', { name: 'Terminer définitivement le groupage', exact: true })
    .click();
  await page.getByRole('button', { name: 'Confirmer la fin du groupage', exact: true }).click();
  await expect(
    page.getByText('Anomalie de paiement à régulariser', { exact: false }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Terminer définitivement le groupage', exact: true }),
  ).toBeDisabled();
  expect(writes()).toBe(1);
});
