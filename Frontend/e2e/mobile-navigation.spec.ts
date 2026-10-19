import { expect, Page, test } from '@playwright/test';
import { mockApi, noOverflow, session } from './api-mock';

async function setup(page: Page, signedIn = true) {
  if (signedIn) await session(page);
  const state = await mockApi(page);
  // Only read contracts used by the destinations in this navigation test.
  await page.route('**/api/organizations/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (/\/(payment-requests|payment-reviews)$/.test(path))
      return route.fulfill({
        json: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
      });
    if (path.endsWith('/merchant-settlement') || path.endsWith('/merchant-balance'))
      return route.fulfill({
        json: {
          currency: 'XOF',
          campaigns: [],
          canManageSettlements: false,
          canManageDestination: false,
          providerAvailable: false,
          availableForPayout: 0,
        },
      });
    if (path.endsWith('/merchant-payouts')) return route.fulfill({ json: [] });
    if (path.endsWith('/payout-destination')) return route.fulfill({ status: 204 });
    return route.fallback();
  });
  return state;
}

for (const [width, height, theme] of [
  [390, 844, 'light'],
  [430, 932, 'dark'],
  [768, 1024, 'light'],
] as const) {
  test(`navigation complète par la barre mobile ${width}x${height} ${theme}`, async ({
    page,
  }, info) => {
    await page.setViewportSize({ width, height });
    await page.addInitScript((value) => localStorage.setItem('groupia.theme', value), theme);
    const state = await setup(page, false);
    await page.goto('/connexion');
    await page.getByLabel('Numéro de téléphone').fill('+221771234567');
    await page.getByLabel('Mot de passe', { exact: true }).fill('une phrase suffisamment longue');
    await page.getByRole('button', { name: 'Se connecter', exact: true }).click();
    await expect(page).toHaveURL(/\/app\/accueil$/);
    const nav = page.getByRole('navigation', { name: 'Navigation mobile', exact: true });
    const more = nav.getByRole('button', { name: /^Plus/ });
    const sheet = page.getByRole('dialog', { name: 'Plus de pages' });
    await expect(nav).toBeVisible();
    await expect(nav.getByRole('link')).toHaveText([
      'Accueil',
      'Groupages',
      'Produits',
      'Commandes',
    ]);
    await expect(nav.getByRole('button')).toHaveCount(1);
    await nav.getByRole('link', { name: 'Produits', exact: true }).click();
    await expect(page).toHaveURL(/\/app\/products$/);
    await expect(page.getByRole('heading', { name: 'Produits', exact: true })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Produits', exact: true })).toHaveAttribute(
      'aria-current',
      'page',
    );

    for (const [label, path, heading] of [
      ['Paiements', 'paiements', 'Paiements'],
      ['Versements', 'versements', 'Mes versements'],
      ['Abonnement', 'abonnement', 'Abonnement'],
      ['Ma boutique', 'boutique', 'Ma boutique'],
    ]) {
      await more.click();
      await expect(sheet).toBeVisible();
      await expect(more).toHaveAttribute('aria-expanded', 'true');
      await expect(sheet.getByRole('link')).toHaveText([
        'Ma boutique',
        'Paiements',
        'Versements',
        'Abonnement',
      ]);
      await noOverflow(page);
      if (path === 'paiements') await page.screenshot({ path: info.outputPath('plus-menu.png') });
      await sheet.getByRole('link', { name: label, exact: true }).click();
      await expect(page).toHaveURL(new RegExp('/app/' + path + '$'));
      await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible();
      await expect(sheet).not.toBeVisible();
      await expect(more).toHaveClass(/active/);
      await expect(nav.locator('a.active')).toHaveCount(0);
      expect(await page.evaluate(() => document.documentElement.style.overflow)).toBe('');
    }
    await more.click();
    await sheet.getByRole('button', { name: 'Se déconnecter', exact: true }).click();
    await expect(page).toHaveURL(/\/connexion/);
    await expect(nav).not.toBeVisible();
    expect(state.calls.filter((call) => call.path === '/api/auth/logout')).toHaveLength(1);
    expect(await page.evaluate(() => document.documentElement.style.overflow)).toBe('');
  });
}

test('Plus : clavier, focus, backdrop et scroll à 390px', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await setup(page);
  await page.goto('/app/accueil');
  const more = page.getByRole('button', { name: 'Plus', exact: true });
  const sheet = page.getByRole('dialog', { name: 'Plus de pages' });
  await more.focus();
  await page.keyboard.press('Enter');
  const close = sheet.getByRole('button', { name: 'Fermer le menu Plus' });
  await expect(close).toBeFocused();
  expect(await close.evaluate((el) => getComputedStyle(el).outlineStyle)).not.toBe('none');
  await page.keyboard.press('Shift+Tab');
  await expect(sheet.getByRole('button', { name: 'Se déconnecter' })).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(close).toBeFocused();
  expect(await page.evaluate(() => document.documentElement.style.overflow)).toBe('hidden');
  const scroll = await page.evaluate(() => window.scrollY);
  await page.mouse.move(10, 20);
  await page.mouse.wheel(0, 500);
  expect(await page.evaluate(() => window.scrollY)).toBe(scroll);
  await page.keyboard.press('Escape');
  await expect(sheet).not.toBeVisible();
  await expect(more).toBeFocused();
  await expect(more).toHaveAttribute('aria-expanded', 'false');
  expect(await page.evaluate(() => document.documentElement.style.overflow)).toBe('');
  await page.keyboard.press('Space');
  await expect(sheet).toBeVisible();
  await page.mouse.click(10, 20);
  await expect(sheet).not.toBeVisible();
  await expect(more).toBeFocused();
  await noOverflow(page);
});

for (const width of [1024, 1440]) {
  test(`desktop ${width}px conserve sa sidebar et son logout`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    await setup(page);
    await page.goto('/app/accueil');
    const sidebar = page.getByRole('navigation', { name: 'Navigation principale', exact: true });
    await expect(sidebar).toBeVisible();
    await expect(sidebar.getByRole('link')).toHaveText([
      'Tableau de bord',
      'Groupages',
      'Produits',
      'Commandes',
      'Paiements',
      'Ma boutique',
      'Mes versements',
      'Abonnement',
    ]);
    await expect(
      page.getByRole('navigation', { name: 'Navigation mobile', exact: true }),
    ).not.toBeVisible();
    await expect(page.getByRole('button', { name: /^Plus/ })).toHaveCount(0);
    await expect(
      page.locator('g-topbar').getByRole('button', { name: 'Se déconnecter' }),
    ).toBeVisible();
    await noOverflow(page);
  });
}

test('un drawer ouvert se ferme quand la tablette passe en desktop', async ({ page }) => {
  await page.setViewportSize({ width: 768, height: 1024 });
  await setup(page);
  await page.goto('/app/accueil');
  await page.getByRole('button', { name: 'Plus', exact: true }).click();
  const sheet = page.getByRole('dialog', { name: 'Plus de pages' });
  await expect(sheet).toBeVisible();
  await page.setViewportSize({ width: 1024, height: 768 });
  await expect(sheet).not.toBeVisible();
  expect(await page.evaluate(() => document.documentElement.style.overflow)).toBe('');
  await expect(
    page.getByRole('navigation', { name: 'Navigation principale', exact: true }),
  ).toBeVisible();
  await page.setViewportSize({ width: 768, height: 1024 });
  await expect(page.getByRole('button', { name: 'Plus', exact: true })).toHaveAttribute(
    'aria-expanded',
    'false',
  );
});
