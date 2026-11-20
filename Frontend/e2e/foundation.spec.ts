import { mockApi, session } from './api-mock';
import { test, expect } from '@playwright/test';
test.beforeEach(async ({ page }, info) => {
  if (!info.title.startsWith('production:')) {
    await session(page);
    await mockApi(page);
  }
});
for (const width of [360, 768, 1024, 1440])
  for (const theme of ['light', 'dark']) {
    test(`dashboard ${width}px ${theme}: lisible sans débordement ni fixture métier`, async ({
      page,
    }) => {
      const calls: string[] = [];
      page.on('request', (r) => {
        if (new URL(r.url()).pathname.startsWith('/api/')) calls.push(r.url());
      });
      await page.setViewportSize({ width, height: 1000 });
      await page.addInitScript((mode) => localStorage.setItem('groupia.theme', mode), theme);
      await page.goto('/accueil');
      await expect(page.getByRole('heading', { name: 'Accueil', exact: true })).toBeVisible();
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
      ).toBe(true);
      await expect(page.locator('g-sales-chart')).toHaveCount(0);
      await expect(page.getByRole('link', { name: 'Voir les statistiques' })).toBeVisible();
      expect(
        calls.every(
          (url) =>
            new URL(url).pathname === '/api/auth/refresh' ||
            new URL(url).pathname === '/api/auth/me' ||
            // Admin rights are checked separately and denied in the seller fixture.
            new URL(url).pathname === '/api/platform/saspay/incidents/access' ||
            new URL(url).pathname === '/api/organizations' ||
            /\/api\/organizations\/[^/]+\/analytics\/business$/.test(new URL(url).pathname) ||
            /\/api\/organizations\/[^/]+\/(?:orders|grouping-campaigns|products)$/.test(
              new URL(url).pathname,
            ) ||
            /\/api\/organizations\/[^/]+\/subscription(?:\/(?:history|invoices|invoice-preview))?$/.test(
              new URL(url).pathname,
            ),
        ),
      ).toBe(true);
      if (width === 360 || width === 1440)
        await page.screenshot({
          path: `artifacts/dashboard-${width}-${theme}.png`,
          fullPage: true,
        });
    });
  }
test('navigation mobile et focus clavier', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await page.goto('/accueil');
  const nav = page.getByRole('navigation', { name: 'Navigation mobile' });
  await nav.getByRole('link', { name: 'Commandes', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Commandes', exact: true })).toBeVisible();
  await expect(nav.getByRole('link', { name: 'Commandes', exact: true })).toHaveAttribute(
    'aria-current',
    'page',
  );
  const menu = page.locator('g-topbar').getByRole('button', { name: 'Menu', exact: true });
  await menu.click();
  const dialog = page.getByRole('dialog', { name: 'Menu Groupia' });
  await expect(dialog.getByRole('button', { name: 'Fermer le menu' })).toBeFocused();
  await dialog.getByRole('link', { name: 'Ma boutique', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Ma boutique', exact: true })).toBeVisible();
  await menu.focus();
  await page.keyboard.press('Shift+Tab');
  await page.keyboard.press('Tab');
  await expect(menu).toBeFocused();
  expect(
    await menu.evaluate((el) => getComputedStyle(el).outlineStyle),
  ).not.toBe('none');
});
test('préférence thème persistée et système respecté', async ({ page }) => {
  await page.goto('/accueil');
  await page.getByLabel('Thème', { exact: true }).selectOption('dark');
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await page.getByLabel('Thème', { exact: true }).selectOption('system');
  await page.emulateMedia({ colorScheme: 'light' });
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
  await page.emulateMedia({ colorScheme: 'dark' });
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
});
test('onglets et toggle accessibles au clavier', async ({ page }) => {
  await page.goto('/app/atelier');
  await page.getByRole('tab', { name: 'Aperçu' }).focus();
  await page.keyboard.press('ArrowRight');
  await expect(page.getByRole('tab', { name: 'Détails' })).toBeFocused();
  await expect(page.getByRole('tabpanel')).toContainText('Contenu des détails');
  await page.getByRole('switch', { name: 'Notifications', exact: true }).focus();
  await page.keyboard.press('Space');
  await expect(page.getByRole('switch')).toHaveAttribute('aria-checked', 'true');
});
test('production: guard actif, routes publiques anonymes', async ({ page }) => {
  await page.route('**/api/auth/refresh', (route) =>
    route.fulfill({ status: 401, json: { code: 'AUTH_REFRESH_INVALID' } }),
  );
  await page.goto('http://127.0.0.1:4300/accueil');
  await expect(page).toHaveURL(/connexion/);
  await expect(page.getByRole('button', { name: 'Se connecter' })).toBeDisabled();
  await expect(page.getByText('Awa')).toHaveCount(0);
  await page.goto('http://127.0.0.1:4300/suivi');
  await expect(page.getByRole('heading', { name: 'Retrouver mes commandes' })).toBeVisible();
  await page.goto('http://127.0.0.1:4300/public/order');
  await expect(page.getByRole('heading', { name: 'Votre suivi' })).toBeVisible();
});
