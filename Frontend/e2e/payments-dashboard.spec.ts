import { expect, test, Page } from '@playwright/test';
import { mockApi, session, shopId, noOverflow } from './api-mock';
import { paymentPage, paymentDetail, paymentReview } from '../src/app/testing/payment-fixtures';
import type {
  PaymentRequestSummaryView,
  ResolvePaymentReviewRefunded,
} from '../src/app/api/generated/models';
async function setup(page: Page, canResolve = true) {
  await session(page);
  await mockApi(page);
  const records: PaymentRequestSummaryView[] = Array.from({ length: 25 }, (_, i) => ({
    ...paymentPage.items![0],
    id: 'request-' + i,
    purpose: i % 2 ? 'SHIPPING_BALANCE' : 'INITIAL_ORDER',
    operationalStatus: (['PENDING', 'SETTLED', 'REVIEW_REQUIRED', 'TERMINAL_UNPAID'] as const)[
      i % 4
    ],
    openReviewCount: i % 4 === 2 ? 1 : 0,
    terminalReason: i % 4 === 3 ? 'DEADLINE_EXCEEDED' : undefined,
  }));
  const state = {
    calls: [] as URL[],
    posts: [] as ResolvePaymentReviewRefunded[],
    review: { ...paymentReview, organizationId: shopId, paymentRequestId: 'request-2', canResolve },
    loseResponse: false,
    saveBeforeLoss: false,
    conflict: false,
    fail: false,
    empty: false,
  };
  await page.route('**/api/organizations/**', async (route) => {
    const req = route.request(),
      url = new URL(req.url()),
      path = url.pathname;
    if (!path.includes('/payment-')) {
      if (path.endsWith('/orders/order'))
        return route.fulfill({
          json: {
            id: 'order',
            order: { id: 'order', orderNumber: 'GRP-TEST', status: 'CONFIRMED', items: [] },
            customerContactSnapshot: { firstName: 'Cliente', lastName: 'Test' },
          },
        });
      return route.fallback();
    }
    state.calls.push(url);
    expect(req.headers()['authorization']).toBe('Bearer e2e-only-token');
    if (state.fail)
      return route.fulfill({ status: 500, json: { detail: 'private SQL information' } });
    if (path.endsWith('/payment-requests')) {
      const status = url.searchParams.get('operationalStatus'),
        purpose = url.searchParams.get('purpose'),
        pageNumber = Number(url.searchParams.get('page') ?? 0),
        size = Number(url.searchParams.get('size') ?? 20);
      const scoped = state.empty ? [] : records.filter((r) => !purpose || r.purpose === purpose),
        filtered = scoped.filter((r) => !status || r.operationalStatus === status);
      return route.fulfill({
        json: {
          items: filtered.slice(pageNumber * size, (pageNumber + 1) * size),
          page: pageNumber,
          size,
          totalElements: filtered.length,
          totalPages: Math.ceil(filtered.length / size),
          summary: {
            pending: scoped.filter((r) => r.operationalStatus === 'PENDING').length,
            settled: scoped.filter((r) => r.operationalStatus === 'SETTLED').length,
            reviewRequired: scoped.filter((r) => r.operationalStatus === 'REVIEW_REQUIRED').length,
            terminalUnpaid: scoped.filter((r) => r.operationalStatus === 'TERMINAL_UNPAID').length,
            openReviews: state.review.status === 'OPEN' ? 1 : 0,
          },
        },
      });
    }
    if (path.includes('/payment-requests/')) {
      const r = records.find((r) => path.endsWith('/' + r.id));
      return route.fulfill({ json: { ...paymentDetail, request: r } });
    }
    if (path.endsWith('/payment-reviews'))
      return route.fulfill({
        json: {
          items: state.review.status === url.searchParams.get('status') ? [state.review] : [],
          page: 0,
          totalPages: 1,
          totalElements: 1,
        },
      });
    if (path.endsWith('/resolve-refunded')) {
      const body = req.postDataJSON() as ResolvePaymentReviewRefunded;
      state.posts.push(body);
      expect(Object.keys(body).sort()).toEqual(['externalRefundReference', 'resolutionNote']);
      if (state.conflict) {
        Object.assign(state.review, {
          status: 'RESOLVED_REFUNDED',
          canResolve: false,
          externalRefundReference: 'OTHER',
        });
        return route.fulfill({ status: 409, json: { code: 'PAYMENT_REVIEW_RESOLUTION_CONFLICT' } });
      }
      if (!state.loseResponse || state.saveBeforeLoss)
        Object.assign(state.review, {
          status: 'RESOLVED_REFUNDED',
          canResolve: false,
          ...body,
          resolvedAt: '2026-10-12T13:00:00Z',
        });
      if (state.loseResponse) {
        state.loseResponse = false;
        return route.abort('failed');
      }
      return route.fulfill({ json: state.review });
    }
    return route.fulfill({ json: state.review });
  });
  return state;
}
async function openReview(page: Page) {
  await page.goto('/app/paiements/reviews/review');
  await expect(
    page.getByRole('heading', { name: 'Paiement à examiner', exact: true }),
  ).toBeVisible();
}
async function fillResolution(page: Page) {
  await page
    .getByRole('button', { name: 'Enregistrer un remboursement externe', exact: true })
    .click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('TX-SNAPSHOT');
  await expect(dialog).toContainText("Cette action n'effectue aucun remboursement.");
  await dialog.getByLabel('Référence du remboursement externe').fill('EXT-TEST');
  await dialog.getByLabel('Note (facultative)').fill('Preuve déjà obtenue');
  return dialog;
}
for (const [width, theme] of [
  [360, 'light'],
  [360, 'dark'],
  [1280, 'light'],
  [1280, 'dark'],
] as const) {
  test('dashboard et dialogue accessibles ' + width + ' ' + theme, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 860 });
    await page.addInitScript((t) => localStorage.setItem('groupia.theme', t), theme);
    const state = await setup(page);
    await page.goto('/app/paiements');
    await expect(page.getByRole('heading', { name: 'Paiements', exact: true })).toBeVisible();
    await expect(page.locator('g-payment-request-list tbody tr')).toHaveCount(20);
    await expect(page.getByText('1 paiements à examiner', { exact: true })).toBeVisible();
    await noOverflow(page);
    await page.screenshot({ path: testInfo.outputPath('dashboard.png'), fullPage: false });
    await page.getByRole('link', { name: 'Examiner la transaction TX-SNAPSHOT' }).click();
    const dialog = await fillResolution(page);
    await noOverflow(page);
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
    await dialog.getByLabel('Référence du remboursement externe').focus();
    await expect(dialog.getByLabel('Référence du remboursement externe')).toHaveCSS(
      'outline-style',
      'solid',
    );
    await page.keyboard.press('Shift+Tab');
    await expect(dialog.getByRole('button', {name:'Confirmer l’enregistrement',exact:true})).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(dialog.getByLabel('Référence du remboursement externe')).toBeFocused();
    expect(await page.evaluate(() => !!document.activeElement?.closest('dialog'))).toBe(true);
    await page.screenshot({ path: testInfo.outputPath('review-dialog.png'), fullPage: false });
    await page.keyboard.press('Escape');
    await expect(dialog).not.toBeVisible();
    expect(state.posts).toHaveLength(0);
  });
}
for (const [label, status] of [
  ['En attente', 'PENDING'],
  ['Réglés', 'SETTLED'],
  ['À vérifier', 'REVIEW_REQUIRED'],
] as const) {
  test('filtre serveur ' + label, async ({ page }) => {
    const state = await setup(page);
    await page.goto('/app/paiements');
    await expect(page.locator('g-payment-request-list tbody tr')).toHaveCount(20);
    const reviewCalls = state.calls.filter((u) => u.pathname.endsWith('/payment-reviews')).length;
    await page.getByRole('button', { name: label, exact: true }).click();
    await expect(page.locator('g-payment-request-list tbody tr')).toHaveCount(
      status === 'PENDING' ? 7 : 6,
    );
    expect(state.calls.at(-1)?.searchParams.get('operationalStatus')).toBe(status);
    expect(state.calls.filter((u) => u.pathname.endsWith('/payment-reviews'))).toHaveLength(
      reviewCalls,
    );
  });
}
test('pagination indépendante puis filtre type remet à la première page', async ({ page }) => {
  const state = await setup(page);
  await page.goto('/app/paiements');
  await expect(page.locator('g-payment-request-list tbody tr')).toHaveCount(20);
  await page
    .getByRole('navigation', { name: 'Pagination des obligations' })
    .getByRole('button', { name: 'Suivant' })
    .click();
  await expect(page.locator('g-payment-request-list tbody tr')).toHaveCount(5);
  await page.getByLabel('Type de paiement').selectOption('SHIPPING_BALANCE');
  await expect(page.locator('g-payment-request-list tbody tr')).toHaveCount(12);
  expect(state.calls.at(-1)?.searchParams.get('page')).toBe('0');
});
test('détail avec toutes les tentatives et navigation vers la commande', async ({ page }) => {
  await setup(page);
  await page.goto('/app/paiements/requests/request-2');
  await expect(page.getByRole('heading', { name: 'Tentatives de paiement' })).toBeVisible();
  await expect(page.locator('.attempt')).toHaveCount(2);
  await expect(page.getByText('TX-PRINCIPAL', { exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Voir la commande', exact: true }).click();
  await expect(page).toHaveURL(/\/app\/orders\/order$/);
});
test('OWNER enregistre une preuve puis consulte l’historique résolu', async ({ page }) => {
  const state = await setup(page);
  await openReview(page);
  const dialog = await fillResolution(page);
  await dialog.getByRole('button', { name: 'Confirmer l’enregistrement', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(
    page.getByText('Le remboursement externe est enregistré.', { exact: true }),
  ).toBeVisible();
  expect(state.posts).toHaveLength(1);
  await page.getByRole('link', { name: '← Paiements' }).click();
  await page.getByLabel('Afficher les incidents').selectOption('RESOLVED_REFUNDED');
  await expect(
    page.getByRole('link', { name: 'Examiner la transaction TX-SNAPSHOT' }),
  ).toBeVisible();
});
test('VIEWER consulte sans action de résolution', async ({ page }) => {
  const state = await setup(page, false);
  await openReview(page);
  await expect(page.getByText(/Consultation uniquement/)).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Enregistrer un remboursement externe', exact: true }),
  ).toHaveCount(0);
  expect(state.posts).toHaveLength(0);
});
test('référence vide bloquée dans le dialogue', async ({ page }) => {
  const state = await setup(page);
  await openReview(page);
  await page
    .getByRole('button', { name: 'Enregistrer un remboursement externe', exact: true })
    .click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: 'Confirmer l’enregistrement', exact: true })
    .click();
  await expect(page.getByRole('dialog').getByRole('alert')).toContainText('référence');
  expect(state.posts).toHaveLength(0);
});
for (const saved of [false, true])
  test('réponse perdue puis relecture ' + (saved ? 'résolue' : 'ouverte'), async ({ page }) => {
    const state = await setup(page);
    state.loseResponse = true;
    state.saveBeforeLoss = saved;
    await openReview(page);
    const dialog = await fillResolution(page);
    await dialog.getByRole('button', { name: 'Confirmer l’enregistrement', exact: true }).click();
    if (!saved) {
      await page.getByRole('button', { name: 'Réessayer l’enregistrement', exact: true }).click();
      expect(state.posts).toHaveLength(2);
      expect(state.posts[1]).toEqual(state.posts[0]);
    }
    await expect(
      page.getByText('Le remboursement externe est enregistré.', { exact: true }),
    ).toBeVisible();
    expect(state.posts).toHaveLength(saved ? 1 : 2);
  });
test('preuve contradictoire visible sans deuxième remboursement', async ({ page }) => {
  const state = await setup(page);
  state.conflict = true;
  await openReview(page);
  const dialog = await fillResolution(page);
  await dialog.getByRole('button', { name: 'Confirmer l’enregistrement', exact: true }).click();
  await expect(page.getByText(/Conflit : une autre preuve/)).toBeVisible();
  await expect(page.getByText('OTHER', { exact: true })).toBeVisible();
  expect(state.posts).toHaveLength(1);
});
