import { expect, test } from '@playwright/test';
import { mockApi, noOverflow, productId, session } from './api-mock';

const extremelyLongName = 'Smartphone Samsung S24 FE 128 Go ' +
  'ProduitSansEspacePourTesterLaResponsivite'.repeat(7);

for (const [width, theme] of [
  [320, 'light'],
  [360, 'dark'],
  [390, 'light'],
  [768, 'dark'],
  [1440, 'light'],
] as const) {
  test('catalogue V3 responsive, recherches et variantes ' + width + 'px ' + theme, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.addInitScript((value) => localStorage.setItem('groupia.theme', value), theme);
    await session(page);
    const state = await mockApi(page);
    state.products[0].name = extremelyLongName;
    state.products[0].createdAt = '2026-10-27T10:00:00Z';
    state.products[0].description = 'DescriptionSansEspace'.repeat(50);
    for (let i = 1; i < 9; i++) {
      state.products.push({
        ...structuredClone(state.products[0]),
        id: 'aaaaaaaa-aaaa-4aaa-8aaa-' + String(i).padStart(12, '0'),
        name: 'Produit exemple ' + i,
        status: i % 2 === 0 ? 'ACTIVE' : 'ARCHIVED',
        createdAt: '2026-10-' + String(i + 1).padStart(2, '0') + 'T10:00:00Z',
        variants: i === 1 ? [
          { id: '00000000-0000-4000-8000-000000000001', active: true, attributes: { color: 'Noir' } },
          { id: '00000000-0000-4000-8000-000000000002', active: true, attributes: { color: 'Blanc' } },
          { id: '00000000-0000-4000-8000-000000000003', active: false, attributes: { color: 'Vert' } },
        ] : state.products[0].variants,
      });
    }

    await page.goto('/app/products');
    await expect(page.getByRole('heading', { name: 'Vos produits' })).toBeVisible();
    await expect(page.locator('.catalog-product')).toHaveCount(6);
    await expect(page.getByRole('button', { name: 'Tous 9' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Actifs 5' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Archivés 4' })).toBeVisible();
    await noOverflow(page);

    await page.getByRole('button', { name: 'Voir les autres produits (3)' }).click();
    await expect(page.locator('.catalog-product')).toHaveCount(9);
    await page.getByRole('button', { name: 'Réduire la liste' }).click();
    await expect(page.locator('.catalog-product')).toHaveCount(6);

    await page.getByRole('button', { name: 'Archivés 4' }).click();
    await expect(page.locator('.catalog-product')).toHaveCount(4);
    await page.getByRole('button', { name: 'Actifs 5' }).click();
    await expect(page.locator('.catalog-product')).toHaveCount(5);
    await page.getByRole('button', { name: 'Tous 9' }).click();

    await page.getByRole('searchbox', { name: 'Rechercher un produit sur cette page' }).fill('Smartphone');
    await expect(page.locator('.catalog-product')).toHaveCount(1);
    await page.locator('.catalog-product .product-actions button').filter({ hasText: 'Variantes' }).click();
    await expect(page.locator('.variant-list li')).toHaveCount(1);
    await page.locator('.product-description summary').click();
    await expect(page.locator('.product-description .preserve')).toBeVisible();
    await noOverflow(page);

    await page.getByRole('button', { name: 'Voir les images' }).click();
    await expect(page.getByRole('dialog', { name: 'Gestion des images du produit' })).toBeVisible();
    await expect(page.locator('g-product-gallery')).toBeVisible();
    await noOverflow(page);
    await page.getByRole('button', { name: 'Fermer' }).click();
    await expect(page.getByRole('dialog', { name: 'Gestion des images du produit' })).not.toBeVisible();

    await page.getByRole('searchbox', { name: 'Rechercher un produit sur cette page' }).fill('Inexistant');
    await expect(page.getByRole('heading', { name: 'Aucun produit correspondant sur cette page' })).toBeVisible();
    await page.getByRole('button', { name: 'Effacer les filtres' }).click();
    await expect(page.locator('.catalog-product')).toHaveCount(6);
    await page.getByRole('combobox', { name: 'Trier les produits de cette page' }).selectOption('variants');
    await expect(page.locator('.catalog-product h3').first()).toHaveText('Produit exemple 1');
    await noOverflow(page);
  });
}

test('catalogue V3 : création conserve le parcours de photos et ne fabrique aucun prix', async ({ page }) => {
  await session(page);
  const state = await mockApi(page, { empty: true });
  state.shops.push({ id: '11111111-1111-4111-8111-111111111111', name: 'Boutique' });
  await page.goto('/app/products');
  await page.getByRole('button', { name: 'Ajouter un produit' }).click();
  const dialog = page.getByRole('dialog', { name: 'Ajouter un produit' });
  await expect(dialog).toBeVisible();
  await dialog.getByLabel('Nom du produit').fill('Écouteurs bluetooth');
  await dialog.getByRole('button', { name: 'Créer le produit' }).click();
  await expect(page.getByRole('dialog', { name: 'Gestion des images du produit' })).toBeVisible();
  await expect(page.getByText('Le prix sera défini dans chaque groupage.')).not.toBeVisible();
  await page.getByRole('dialog', { name: 'Gestion des images du produit' })
    .getByRole('button', { name: 'Fermer', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Gestion des images du produit' })).not.toBeVisible();
  await expect(page.locator('.catalog-product')).toHaveCount(1);
  expect(state.products[0].name).toBe('Écouteurs bluetooth');
  await noOverflow(page);
});
