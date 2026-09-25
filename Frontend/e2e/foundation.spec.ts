import { test, expect } from '@playwright/test';
for (const width of [360, 768, 1024, 1440])
  for (const theme of ['light', 'dark']) {
    test(`dashboard ${width}px ${theme}: lisible sans débordement ni API`, async ({ page }) => {
      const calls: string[] = [];
      page.on('request', (r) => {
        if (new URL(r.url()).pathname.startsWith('/api/')) calls.push(r.url());
      });
      await page.setViewportSize({ width, height: 1000 });
      await page.addInitScript((mode) => localStorage.setItem('groupia.theme', mode), theme);
      await page.goto('/accueil');
      await expect(page.getByRole('heading', { name: 'Bonjour Awa !' })).toBeVisible();
      await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
      expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
      ).toBe(true);
      await expect(page.locator('g-stat-card')).toHaveCount(4);
      expect(calls).toHaveLength(0);
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
  await nav.getByRole('link', { name: 'Boutique', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Ma boutique', exact: true })).toBeVisible();
  await page.keyboard.press('Shift+Tab');
  await expect(nav.getByRole('link', { name: 'Commandes', exact: true })).toBeFocused();
  expect(
    await page.evaluate(() => getComputedStyle(document.activeElement!).outlineStyle),
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
  await page.goto('/atelier');
  await page.getByRole('tab', { name: 'Aperçu' }).focus();
  await page.keyboard.press('ArrowRight');
  await expect(page.getByRole('tab', { name: 'Détails' })).toBeFocused();
  await expect(page.getByRole('tabpanel')).toContainText('Contenu des détails');
  await page.getByRole('switch', { name: 'Notifications', exact: true }).focus();
  await page.keyboard.press('Space');
  await expect(page.getByRole('switch')).toHaveAttribute('aria-checked', 'true');
});
test('production: guard actif, routes publiques anonymes', async ({ page }) => {
  await page.goto('http://127.0.0.1:4300/accueil');
  await expect(page).toHaveURL(/connexion/);
  await expect(page.getByRole('button', { name: 'Se connecter' })).toBeDisabled();
  await expect(page.getByText('Awa')).toHaveCount(0);
  await page.goto('http://127.0.0.1:4300/suivi');
  await expect(page.getByRole('heading', { name: 'Suivre ma commande' })).toBeVisible();
});
