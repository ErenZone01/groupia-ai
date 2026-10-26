import { expect, test } from '@playwright/test';
import { groupId, mockApi, noOverflow, session } from './api-mock';

for (const status of ['DRAFT', 'OPEN'] as const) {
  for (const [width, height, theme] of [
    [360, 780, 'light'],
    [390, 844, 'dark'],
    [1280, 900, 'light'],
  ] as const) {
    test(`détail V2 ${status}, ${width}px ${theme} : compact et actions utiles`, async ({ page }, info) => {
      await page.setViewportSize({ width, height });
      await page.addInitScript((value) => localStorage.setItem('groupia.theme', value), theme);
      await session(page);
      const state = await mockApi(page, { status });
      state.groups[0].closesAt = '2026-11-01T10:30:00Z';
      await page.goto('/app/groupages/' + groupId);

      const hero = page.locator('.detail-hero');
      await expect(hero.getByRole('heading', { name: 'Les essentiels de la rentrée' })).toBeVisible();
      await expect(hero.locator('.detail-meta')).toContainText('10:30');
      await expect(hero.locator('.detail-meta')).toContainText('1 offre(s)');
      await expect(hero.locator('.detail-next-step')).not.toBeEmpty();
      await expect(page.getByRole('tab', { name: 'Offres' })).toBeVisible();
      await expect(page.getByRole('tab', { name: 'Commandes' })).toBeVisible();
      await expect(page.getByRole('tab', { name: 'Infos' })).toBeVisible();
      await expect(page.getByRole('tab', { name: 'Aperçu' })).toHaveCount(0);
      await expect(page.getByRole('tab', { name: 'Fournisseur' })).toHaveCount(0);
      await expect(page.locator('g-logistics-panel, g-merchant-funds-panel, g-completion-panel')).toHaveCount(0);
      if (status === 'OPEN') {
        const share = page.getByRole('region', { name: 'Partager le groupage' });
        await expect(share.getByRole('link', { name: 'Voir comme une cliente' })).toBeVisible();
        await expect(share.locator('.share-link')).not.toBeVisible();
        await share.locator('summary').click();
        await expect(share.locator('.share-link')).toBeVisible();
        await expect(hero.getByRole('button', { name: 'Copier le lien' })).toBeVisible();
        await page.getByRole('tab', { name: 'Commandes' }).click();
        await page.getByRole('button', { name: 'Fermer les commandes' }).click();
        await expect(page.getByRole('group', { name: 'Confirmation de fermeture' })).toBeVisible();
        await page.getByRole('button', { name: 'Annuler' }).click();
        await expect(page.getByRole('group', { name: 'Confirmation de fermeture' })).toHaveCount(0);
      } else {
        await expect(hero.getByRole('button', { name: 'Publier le groupage' })).toBeVisible();
        await expect(page.getByRole('region', { name: 'Partager le groupage' })).toHaveCount(0);
      }
      await page.getByRole('tab', { name: 'Infos' }).click();
      const shipping = page.locator('.shipping-summary');
      await expect(shipping.getByRole('heading', { name: 'Livraison' })).toBeVisible();
      await expect(shipping.locator('dt')).toHaveText(['Transport', 'Tarification', 'Perception', 'Tarif']);
      await expect(shipping).toContainText('Bateau');
      await expect(page.locator('.info-description .preserve')).not.toBeVisible();
      await page.locator('.info-description summary').click();
      await expect(page.locator('.info-description .preserve')).toBeVisible();
      await expect(page.locator('.settings-disclosure')).toBeVisible();
      await noOverflow(page);
      await page.screenshot({ path: info.outputPath(`detail-v2-${status.toLowerCase()}-${width}-${theme}.png`), fullPage: true });
    });
  }
}

for (const [status, expected, absent] of [
  ['CLOSED', ['Fournisseur', 'Finances'], ['Transport', 'Retraits', 'Bilan']],
  ['SUPPLIER_ORDERED', ['Fournisseur', 'Transport', 'Finances'], ['Retraits', 'Bilan']],
  ['ARRIVED', ['Transport', 'Retraits', 'Finances'], ['Bilan']],
  ['DISTRIBUTING', ['Retraits', 'Bilan', 'Finances'], []],
  ['COMPLETED', ['Retraits', 'Bilan', 'Finances'], []],
  ['CANCELLED', ['Offres', 'Commandes', 'Infos'], ['Fournisseur', 'Transport', 'Finances']],
] as const) {
  test(`détail V2 ${status} : sections adaptées sans surcharge`, async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await session(page);
    await mockApi(page, { status });
    await page.goto('/app/groupages/' + groupId);
    for (const label of expected) await expect(page.getByRole('tab', { name: label })).toBeVisible();
    for (const label of absent) await expect(page.getByRole('tab', { name: label })).toHaveCount(0);
    await expect(page.locator('g-logistics-panel, g-merchant-funds-panel, g-completion-panel')).toHaveCount(0);
    await noOverflow(page);
  });
}

test('détail V2 : anciens liens, navigation au clavier et retour', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await session(page);
  await mockApi(page, { status: 'OPEN' });
  await page.goto('/app/groupages/' + groupId + '?tab=overview');
  await expect(page.getByRole('tab', { name: 'Infos' })).toHaveAttribute('aria-selected', 'true');
  await page.getByRole('tab', { name: 'Infos' }).focus();
  await page.keyboard.press('Home');
  await expect(page.getByRole('tab', { name: 'Offres' })).toHaveAttribute('aria-selected', 'true');
  await page.getByRole('link', { name: 'Tous les groupages' }).click();
  await expect(page).toHaveURL(/\/app\/groupages$/);
  await noOverflow(page);
});
