import { expect, test } from '@playwright/test';
import { mockApi, noOverflow, session } from './api-mock';

for (const [width, theme] of [[360, 'dark'], [1440, 'light']] as const) {
  test(`création groupage UX V1 — étapes, aperçu et variantes ${width}px ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 880 });
    await page.addInitScript((value) => localStorage.setItem('groupia.theme', value), theme);
    await session(page);
    await mockApi(page);
    await page.goto('/app/groupages/nouveau');

    await expect(page.getByRole('heading', { name: 'Créer un groupage' })).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Étapes de création du groupage' })).toBeVisible();
    await expect(page.locator('.summary-panel')).toBeVisible();

    await page.getByLabel('Titre', { exact: true }).fill('Sélection de novembre');
    await expect(page.locator('.preview-cover')).toContainText('Sélection de novembre');
    await page.getByRole('button', { name: 'Continuer', exact: true }).click();

    await page.getByRole('button', { name: 'Bateau' }).click();
    await expect(page.getByRole('button', { name: 'Bateau' })).toHaveAttribute('aria-pressed', 'true');
    await page.getByLabel('Tarification de la livraison').selectOption('FIXED');
    await page.getByLabel('Tarif de livraison (F CFA)').fill('2000');
    await page.getByRole('button', { name: 'Continuer', exact: true }).click();

    await expect(page.getByRole('heading', { name: 'Quels produits proposer ?' })).toBeVisible();
    await expect(page.locator('.product-tile').first()).toBeVisible();
    await page.getByLabel('Produit et variante').selectOption({ label: 'Chemise en lin · Standard' });
    await page.getByLabel('Prix dans ce groupage (F CFA)').fill('5500');
    await page.getByRole('button', { name: 'Ajouter au groupage', exact: true }).click();
    await expect(page.getByText('5 500', { exact: false }).first()).toBeVisible();
    await page.getByRole('button', { name: 'Continuer', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Vérifiez avant de publier' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Publier le groupage' })).toBeEnabled();
    await noOverflow(page);

    if (width === 360) {
      await page.locator('.summary-toggle').click();
      await expect(page.locator('.summary-body')).toBeVisible();
      await noOverflow(page);
    }
  });
}

test('création groupage UX V1 — brouillon sans produit et accès aux étapes déjà visitées', async ({ page }) => {
  await session(page);
  await mockApi(page);
  await page.goto('/app/groupages/nouveau');
  await page.getByLabel('Titre', { exact: true }).fill('Brouillon de test');
  await page.getByRole('button', { name: 'Continuer', exact: true }).click();
  await page.getByLabel('Tarification de la livraison').selectOption('FIXED');
  await page.getByLabel('Tarif de livraison (F CFA)').fill('0');
  await page.getByRole('button', { name: 'Continuer', exact: true }).click();
  await page.getByRole('button', { name: 'Continuer', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Publier le groupage' })).toBeDisabled();
  await page.getByRole('button', { name: 'Étape 1 : Informations' }).click();
  await expect(page.getByLabel('Titre', { exact: true })).toHaveValue('Brouillon de test');
  await page.getByRole('button', { name: 'Étape 4 : Vérification' }).click();
  await expect(page.getByRole('button', { name: 'Enregistrer comme brouillon' })).toBeEnabled();
});
