import { expect, Page, test } from '@playwright/test';
import { mockApi, noOverflow, session, shopId } from './api-mock';
import { analyticsFixture } from './business-dashboard-fixture';

async function setup(page: Page) {
  await session(page);
  await mockApi(page, { multiple: true });
  const calls: number[] = [];
  await page.route('**/analytics/business?*', (route) => {
    const months = Number(new URL(route.request().url()).searchParams.get('months'));
    calls.push(months);
    return route.fulfill({ json: analyticsFixture(months) });
  });
  return calls;
}
const normalized = (text: string) => text.replace(/\s/g, '');

for (const width of [360, 390, 430, 768, 1024, 1280, 1440])
  for (const theme of ['light', 'dark']) {
    test(`analytics responsive, clavier et tactile ${width} ${theme}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 960 });
      await page.addInitScript((t) => localStorage.setItem('groupia.theme', t), theme);
      const calls = await setup(page);
      await page.goto('/app/accueil');
      await expect(page.locator('.metric-value').first()).toContainText('FCFA');
      await expect(page.getByLabel('Mois à explorer')).toHaveValue('11');
      await noOverflow(page);
      expect(calls).toEqual([12]);
      for (const value of [3, 6, 12]) {
        const button = page.getByRole('button', { name: `${value} mois`, exact: true });
        expect((await button.boundingBox())!.height).toBeGreaterThanOrEqual(44);
        await button.click();
        await expect(button).toHaveAttribute('aria-pressed', 'true');
        await expect(page.locator('#chart-month option')).toHaveCount(value);
        expect(normalized(await page.locator('.metric-value').first().innerText())).toBe(
          `${analyticsFixture(value).goodsSales}FCFA`,
        );
      }
      const select = page.getByLabel('Mois à explorer');
      await select.focus();
      await page.keyboard.press('Home');
      await expect(select).toHaveValue('0');
      await page.keyboard.press('ArrowRight');
      await expect(select).toHaveValue('1');
      await expect(page.locator('.point-values')).toContainText('février 2026');
      await page.getByRole('button', { name: 'Mois suivant', exact: true }).click();
      await expect(select).toHaveValue('2');
      const table = page.getByText('Consulter le tableau des valeurs', { exact: true });
      await table.focus();
      await page.keyboard.press('Enter');
      await expect(page.locator('g-sales-chart tbody tr')).toHaveCount(12);
      await noOverflow(page);
      await page.emulateMedia({ reducedMotion: 'reduce' });
      await page.evaluate(() => (document.documentElement.style.fontSize = '200%'));
      await noOverflow(page);
    });
  }

test('montants et comparaison exacts, absence de doublon de requêtes opérationnelles', async ({
  page,
}) => {
  const calls = await setup(page);
  const orders: string[] = [];
  page.on('request', (r) => {
    if (/\/orders\?/.test(r.url())) orders.push(r.url());
  });
  await page.goto('/app/accueil');
  await expect(page.locator('.metric-value')).toHaveCount(3);
  await expect(page.locator('.metric-value').first()).toContainText('FCFA');
  const data = analyticsFixture();
  expect(normalized(await page.locator('.metric-value').nth(1).innerText())).toBe(
    String(data.confirmedOrders),
  );
  expect(normalized(await page.locator('.metric-value').nth(2).innerText())).toBe(
    `${data.averageBasket}FCFA`,
  );
  await expect(page.getByText('Même période N−1', { exact: true })).toBeVisible();
  await expect(page.getByText('Toutes périodes', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '3 mois', exact: true }).click();
  await expect(page.locator('#chart-month option')).toHaveCount(3);
  expect(calls).toEqual([12, 3]);
  expect(orders).toHaveLength(4);
  expect(orders.every((url) => Number(new URL(url).searchParams.get('size')) <= 5)).toBe(true);
});

test('déconnexion pendant une requête : réponse tardive ignorée', async ({ page }) => {
  await setup(page);
  let release!: () => void;
  await page.route('**/analytics/business?months=3', async (route) => {
    await new Promise<void>((resolve) => (release = resolve));
    await route.fulfill({ json: analyticsFixture(3) });
  });
  await page.goto('/app/accueil');
  await expect(page.locator('.metric-value')).toHaveCount(3);
  const pending = page.waitForRequest('**/analytics/business?months=3');
  await page.getByRole('button', { name: '3 mois', exact: true }).click();
  await pending;
  await page.getByRole('button', { name: 'Se déconnecter', exact: true }).click();
  await expect(page).toHaveURL(/connexion/);
  release();
  await expect(page.locator('.metric-value')).toHaveCount(0);
});

test.describe('Graphique tactile', () => {
  test.use({ hasTouch: true, viewport: { width: 390, height: 844 } });
  test('explore les points au doigt sans survol', async ({ page }) => {
    await setup(page);
    await page.goto('/app/accueil');
    const select = page.getByLabel('Mois à explorer');
    await expect(select).toHaveValue('11');
    await page.getByRole('button', { name: 'Mois précédent', exact: true }).tap();
    await expect(select).toHaveValue('10');
    await expect(page.locator('.point-values')).toContainText('novembre 2026');
    expect(normalized(await page.locator('.point-values strong').first().innerText())).toBe(
      '86000FCFA',
    );
    await noOverflow(page);
  });
});

test('zéro, panier absent et comparaison indéfinie restent explicites', async ({ page }) => {
  await setup(page);
  await page.route('**/analytics/business?*', (r) =>
    r.fulfill({ json: analyticsFixture(12, true) }),
  );
  await page.goto('/app/accueil');
  await expect(page.locator('.metric-value')).toHaveText(['0 FCFA', '0', '—']);
  await expect(page.locator('.variation')).toHaveText([
    'Comparaison indisponible',
    'Comparaison indisponible',
  ]);
  await expect(
    page.getByText('Aucune vente confirmée sur cette période', { exact: true }),
  ).toBeVisible();
  await expect(page.locator('g-sales-chart .current-line')).not.toHaveAttribute(
    'd',
    /NaN|Infinity/,
  );
});

test('erreur puis retry, chiffres imprécis refusés', async ({ page }) => {
  await setup(page);
  let attempt = 0;
  await page.route('**/analytics/business?*', (r) => {
    attempt++;
    return r.fulfill(
      attempt === 1
        ? { status: 503, json: {} }
        : attempt === 2
          ? { json: { ...analyticsFixture(), goodsSales: 9007199254740992 } }
          : { json: analyticsFixture() },
    );
  });
  await page.goto('/app/accueil');
  await expect(page.locator('g-business-overview [role=alert]')).toContainText('indisponibles');
  await expect(page.locator('.metric-value')).toHaveCount(0);
  await page.getByRole('button', { name: 'Réessayer les statistiques' }).click();
  await expect(page.locator('g-business-overview [role=alert]')).toContainText('précision');
  await page.getByRole('button', { name: 'Réessayer les statistiques' }).click();
  await expect(page.locator('.metric-value')).toHaveCount(3);
});

test('une ancienne période ne peut remplacer une réponse plus récente', async ({ page }) => {
  await setup(page);
  let release!: () => void;
  await page.route('**/analytics/business?months=3', async (route) => {
    await new Promise<void>((resolve) => (release = resolve));
    await route.fulfill({ json: analyticsFixture(3) });
  });
  await page.goto('/app/accueil');
  await expect(page.locator('.metric-value')).toHaveCount(3);
  const pending = page.waitForRequest('**/analytics/business?months=3');
  await page.getByRole('button', { name: '3 mois', exact: true }).click();
  await pending;
  await expect(page.locator('.metric-value')).toHaveCount(0);
  await page.getByRole('button', { name: '6 mois', exact: true }).click();
  await expect(page.locator('#chart-month option')).toHaveCount(6);
  release();
  await expect(page.getByRole('button', { name: '6 mois', exact: true })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  expect(normalized(await page.locator('.metric-value').first().innerText())).toBe(
    `${analyticsFixture(6).goodsSales}FCFA`,
  );
});

test('changement de boutique pendant une requête et retour : aucune fuite', async ({ page }) => {
  await setup(page);
  let release!: () => void;
  await page.route(`**/organizations/${shopId}/analytics/business?*`, async (route) => {
    await new Promise<void>((resolve) => (release = resolve));
    await route.fulfill({ json: { ...analyticsFixture(), goodsSales: 999999 } });
  });
  await page.goto('/app/accueil');
  await expect(page.getByText('Chargement des statistiques…', { exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Ouvrir ma boutique' }).click();
  await page.getByLabel('Changer de boutique').selectOption('77777777-7777-4777-8777-777777777777');
  await expect(page.locator('.welcome .eyebrow')).toHaveText('Deuxième boutique');
  await expect(page.locator('.metric-value')).toHaveCount(3);
  release();
  expect(normalized(await page.locator('.metric-value').first().innerText())).toBe(
    `${analyticsFixture().goodsSales}FCFA`,
  );
});

test('expiration pendant le chargement : aucune statistique après perte de session', async ({
  page,
}) => {
  await setup(page);
  await page.route('**/analytics/business?*', async (route) => {
    await page.context().clearCookies();
    return route.fulfill({ status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } });
  });
  await page.goto('/app/accueil');
  await expect(page).toHaveURL(/connexion/);
  await expect(page.locator('.metric-value')).toHaveCount(0);
});
