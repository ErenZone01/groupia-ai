import { expect, test } from '@playwright/test';
import { groupId, mockApi, noOverflow, session } from './api-mock';

for (const [status, title, share] of [
  ['DRAFT', 'Brouillon', false],
  ['OPEN', 'Ouvert aux commandes', true],
] as const) {
  for (const [width, height, theme] of [
    [390, 844, 'light'],
    [1280, 900, 'dark'],
  ] as const) {
    test(`détail V1 ${status}, ${width}px ${theme} : statut, étape, livraison`, async ({ page }, info) => {
      await page.setViewportSize({ width, height });
      await page.addInitScript((value) => localStorage.setItem('groupia.theme', value), theme);
      await session(page);
      const state = await mockApi(page, { status });
      state.groups[0].closesAt = '2026-11-01T10:30:00Z';
      await page.goto('/app/groupages/' + groupId);
      const hero = page.locator('.detail-hero');
      await expect(hero.getByRole('heading', { name: 'Les essentiels de la rentrée' })).toBeVisible();
      await expect(hero).toContainText(title);
      await expect(hero.locator('.detail-next-step')).not.toBeEmpty();
      await expect(hero.locator('.detail-facts dt')).toHaveText([
        'Clôture prévue', 'Articles du groupage', 'Partage',
      ]);
      await expect(hero.locator('.detail-facts')).toContainText('10:30');
      await expect(hero.locator('.detail-facts')).toContainText('Dakar');
      const shipping = page.locator('.shipping-summary');
      await expect(shipping.getByRole('heading', { name: 'Conditions de livraison' })).toBeVisible();
      await expect(shipping.locator('dt')).toHaveText([
        'Transport', 'Tarification', 'Perception du transport', 'Tarif indiqué',
      ]);
      await expect(shipping).toContainText('Bateau');
      await expect(shipping).toContainText('2');
      if (share) {
        await expect(page.getByRole('region', { name: 'Partager le groupage' })).toBeVisible();
        await expect(page.getByRole('link', { name: 'Voir comme une cliente' })).toBeVisible();
        await expect(page.getByRole('button', { name: 'Fermer les commandes' })).toBeVisible();
      } else {
        await expect(page.getByRole('region', { name: 'Partager le groupage' })).toHaveCount(0);
        await expect(page.getByRole('button', { name: 'Publier le groupage' })).toBeVisible();
      }
      await expect(page.getByRole('tab', { name: 'Produits' })).toBeVisible();
      await noOverflow(page);
      await page.screenshot({
        path: info.outputPath(`detail-${status.toLowerCase()}-${width}-${theme}.png`),
        fullPage: true,
      });
    });
  }
}

test('détail V1 : onglets fonctionnels et retour à la liste', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await session(page);
  await mockApi(page);
  await page.goto('/app/groupages/' + groupId);
  await page.getByRole('tab', { name: 'Produits' }).click();
  await expect(page.getByRole('heading', { name: 'Produits du groupage' })).toBeVisible();
  await page.getByRole('tab', { name: 'Aperçu' }).click();
  await expect(page.getByRole('heading', { name: 'Conditions de livraison' })).toBeVisible();
  await page.getByRole('link', { name: 'Tous les groupages' }).click();
  await expect(page).toHaveURL(/\/app\/groupages$/);
  await noOverflow(page);
});
