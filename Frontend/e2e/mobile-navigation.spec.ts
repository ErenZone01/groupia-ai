import { expect, Page, test } from '@playwright/test';
import { mockApi, noOverflow, session } from './api-mock';

async function setup(page: Page) {
  await session(page);
  await mockApi(page);
  await page.route('**/api/organizations/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (/\/(payment-requests|payment-reviews)$/.test(path))
      return route.fulfill({
        json: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
      });
    if (path.endsWith('/merchant-settlement') || path.endsWith('/merchant-balance'))
      return route.fulfill({
        json: {
          currency: 'XOF', campaigns: [], canManageSettlements: false,
          canManageDestination: false, providerAvailable: false, availableForPayout: 0,
        },
      });
    if (path.endsWith('/merchant-payouts')) return route.fulfill({ json: [] });
    if (path.endsWith('/payout-destination')) return route.fulfill({ status: 204 });
    return route.fallback();
  });
}

for (const [width, height, theme] of [
  [360, 800, 'light'],
  [390, 844, 'light'],
  [430, 932, 'dark'],
  [768, 1024, 'light'],
] as const) {
  test(`navigation V6 mobile ${width}x${height} ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height });
    await page.addInitScript((value) => localStorage.setItem('groupia.theme', value), theme);
    await setup(page);
    await page.goto('/app/accueil');
    const nav = page.getByRole('navigation', { name: 'Navigation mobile', exact: true });
    await expect(nav.getByRole('link')).toHaveText([
      'Accueil', 'Groupages', 'Commandes', 'Produits',
    ]);
    await expect(nav.getByRole('button')).toHaveCount(0);
    const menu = page.locator('g-topbar').getByRole('button', { name: 'Menu', exact: true });
    const sheet = page.getByRole('dialog', { name: 'Menu Groupia' });
    await menu.click();
    await expect(menu).toHaveAttribute('aria-expanded', 'true');
    await expect(sheet).toBeVisible();
    await expect(sheet.getByRole('link')).toHaveText([
      'Vente directe', 'Paiements', 'Versements', 'Statistiques', 'Ma boutique', 'Abonnement',
    ]);
    await noOverflow(page);
    await sheet.getByRole('link', { name: 'Statistiques', exact: true }).click();
    await expect(page).toHaveURL(/\/app\/statistiques$/);
    await expect(sheet).not.toBeVisible();
    await expect(menu).toHaveAttribute('aria-expanded', 'false');
    await nav.getByRole('link', { name: 'Groupages' }).click();
    await expect(page).toHaveURL(/\/app\/groupages$/);
    await expect(nav.getByRole('link', { name: 'Groupages' })).toHaveAttribute(
      'aria-current', 'page',
    );
    await noOverflow(page);
  });
}

test('tiroir droit V6 : clavier, focus, thème, backdrop et scroll', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await setup(page);
  await page.goto('/app/accueil');
  const menu = page.locator('g-topbar').getByRole('button', { name: 'Menu', exact: true });
  const sheet = page.getByRole('dialog', { name: 'Menu Groupia' });
  await menu.focus();
  await page.keyboard.press('Enter');
  const close = sheet.getByRole('button', { name: 'Fermer le menu' });
  await expect(close).toBeFocused();
  expect(await close.evaluate((el) => getComputedStyle(el).outlineStyle)).not.toBe('none');
  const theme = sheet.getByLabel('Apparence');
  await theme.selectOption('dark');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await close.focus();
  await page.keyboard.press('Shift+Tab');
  await expect(sheet.getByRole('button', { name: 'Se déconnecter' })).toBeFocused();
  await page.keyboard.press('Tab');
  await expect(close).toBeFocused();
  expect(await page.evaluate(() => document.documentElement.style.overflow)).toBe('hidden');
  const scroll = await page.evaluate(() => window.scrollY);
  await page.mouse.move(10, 30);
  await page.mouse.wheel(0, 500);
  expect(await page.evaluate(() => window.scrollY)).toBe(scroll);
  await page.keyboard.press('Escape');
  await expect(sheet).not.toBeVisible();
  await expect(menu).toBeFocused();
  expect(await page.evaluate(() => document.documentElement.style.overflow)).toBe('');
  await menu.click();
  // L'ouverture passe par un signal Angular : attendre que le dialogue natif
  // soit effectivement dans la top layer avant de tester le clic extérieur.
  await expect(sheet).toBeVisible();
  await expect(menu).toHaveAttribute('aria-expanded', 'true');
  // Cibler la vraie zone de dismissal, hors du panneau à droite.
  await sheet.locator('.drawer-dismiss-layer').click({ position: { x: 10, y: 20 } });
  await expect(sheet).not.toBeVisible();
  await expect(menu).toHaveAttribute('aria-expanded', 'false');
  await noOverflow(page);
});

test('création rapide n’offre que les actions existantes', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await setup(page);
  await page.goto('/app/accueil');
  const quick = page.locator('g-topbar .quick-create');
  await quick.locator('summary').click();
  await expect(quick).toHaveAttribute('open', '');
  await expect(quick.getByRole('link')).toHaveText([
    'Créer un groupage', 'Ajouter un produit dans le catalogue',
  ]);
  await quick.getByRole('link', { name: 'Ajouter un produit dans le catalogue' }).click();
  await expect(page).toHaveURL(/\/app\/products$/);
  await expect(quick).not.toHaveAttribute('open', '');
  await quick.locator('summary').click();
  await page.keyboard.press('Escape');
  await expect(quick).not.toHaveAttribute('open', '');
  await noOverflow(page);
});

for (const width of [1024, 1280, 1440]) {
  test(`desktop V6 ${width}px : sidebar structurée, menu mobile masqué`, async ({ page }) => {
    await page.setViewportSize({ width, height: 960 });
    await setup(page);
    await page.goto('/app/accueil');
    const sidebar = page.getByRole('navigation', { name: 'Navigation principale', exact: true });
    await expect(sidebar).toBeVisible();
    await expect(sidebar.getByRole('link')).toHaveText([
      'Accueil', 'Groupages', 'Vente directe', 'Commandes', 'Produits',
      'Paiements', 'Mes versements', 'Statistiques', 'Ma boutique', 'Abonnement',
    ]);
    await expect(page.locator('g-sidebar').getByText('Espace de travail')).toBeVisible();
    await expect(page.locator('g-sidebar').getByText('Finances et analyse')).toBeVisible();
    await expect(page.locator('g-topbar .menu-trigger')).toBeHidden();
    await expect(page.getByRole('navigation', { name: 'Navigation mobile' })).not.toBeVisible();
    await expect(page.locator('g-topbar .quick-create summary')).toBeVisible();
    await noOverflow(page);
  });
}

test('un tiroir ouvert se ferme au passage au desktop', async ({ page }) => {
  await page.setViewportSize({ width: 768, height: 1024 });
  await setup(page);
  await page.goto('/app/accueil');
  await page.locator('g-topbar').getByRole('button', { name: 'Menu' }).click();
  const sheet = page.getByRole('dialog', { name: 'Menu Groupia' });
  await expect(sheet).toBeVisible();
  await page.setViewportSize({ width: 1024, height: 768 });
  await expect(sheet).not.toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.style.overflow)).toBe('');
  await expect(page.getByRole('navigation', { name: 'Navigation principale' })).toBeVisible();
});
