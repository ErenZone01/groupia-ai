import { expect, Page, test } from '@playwright/test';
import { mockApi, noOverflow, productId, session } from './api-mock';

async function mutableCatalog(page: Page, state: Awaited<ReturnType<typeof mockApi>>) {
  let conflict = false;
  await page.route('**/api/organizations/*/products/**', async route => {
    const req = route.request();
    const path = new URL(req.url()).pathname;
    if (req.method() === 'GET') return route.fallback();
    if (path.endsWith('/archive') && req.method() === 'POST') {
      const item = state.products.find(p => p.id === path.split('/').at(-2));
      if (!item) return route.fulfill({ status: 404, json: { code: 'PRODUCT_NOT_FOUND' } });
      if (item.version !== req.postDataJSON().version)
        return route.fulfill({ status: 409, json: { code: 'PRODUCT_VERSION_CONFLICT' } });
      item.status = 'ARCHIVED';
      item.version = (item.version ?? 0) + 1;
      return route.fulfill({ json: item });
    }
    if (path.endsWith('/restore') && req.method() === 'POST') {
      const item = state.products.find(p => p.id === path.split('/').at(-2));
      if (!item) return route.fulfill({ status: 404, json: { code: 'PRODUCT_NOT_FOUND' } });
      if (item.version !== req.postDataJSON().version)
        return route.fulfill({ status: 409, json: { code: 'PRODUCT_VERSION_CONFLICT' } });
      if (item.status !== 'ARCHIVED')
        return route.fulfill({ status: 409, json: { code: 'PRODUCT_NOT_ARCHIVED' } });
      item.status = 'ACTIVE';
      item.version = (item.version ?? 0) + 1;
      return route.fulfill({ json: item });
    }
    if (path.endsWith('/variants') && req.method() === 'POST') {
      const item = state.products.find(p => p.id === path.split('/').at(-2));
      if (!item) return route.fulfill({ status: 404, json: { code: 'PRODUCT_NOT_FOUND' } });
      if (item.status !== 'ACTIVE')
        return route.fulfill({ status: 409, json: { code: 'PRODUCT_ARCHIVED' } });
      if (conflict || item.version !== req.postDataJSON().version) {
        conflict = false;
        return route.fulfill({ status: 409, json: { code: 'PRODUCT_VERSION_CONFLICT' } });
      }
      const variant = req.postDataJSON().variant;
      const normalized = Object.fromEntries(Object.entries(variant.attributes)
        .map(([key, val]) => [key.toLowerCase(), String(val).toLowerCase()]));
      if (item.variants?.some(v => JSON.stringify(v.attributes) === JSON.stringify(normalized)))
        return route.fulfill({ status: 409, json: { code: 'DUPLICATE_VARIANT' } });
      item.variants ??= [];
      item.variants.push({ id: '99999999-9999-4999-8999-999999999999',
        attributes: normalized, sku: variant.sku,
        estimatedUnitWeightKg: variant.estimatedUnitWeightKg,
        estimatedUnitVolumeM3: variant.estimatedUnitVolumeM3,
        active: true });
      item.version = (item.version ?? 0) + 1;
      return route.fulfill({ json: item });
    }
    if (req.method() === 'PUT') {
      const item = state.products.find(p => p.id === path.split('/').at(-1));
      if (!item) return route.fulfill({ status: 404, json: { code: 'PRODUCT_NOT_FOUND' } });
      if (conflict || item.version !== req.postDataJSON().version) {
        conflict = false;
        return route.fulfill({ status: 409, json: { code: 'PRODUCT_VERSION_CONFLICT' } });
      }
      item.name = req.postDataJSON().name;
      item.description = req.postDataJSON().description ?? '';
      item.version = (item.version ?? 0) + 1;
      return route.fulfill({ json: item });
    }
    return route.fallback();
  });
  return {
    conflictNext() { conflict = true; },
  };
}

for (const width of [320, 390, 1440]) {
  test(`modifier un produit, version et historique préservés à ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 850 });
    await session(page);
    const state = await mockApi(page);
    const mock = await mutableCatalog(page, state);
    await page.goto('/app/products');
    await page.getByRole('button', { name: 'Modifier', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Modifier un produit' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByLabel('Nom du produit')).toHaveValue('Chemise en lin');
    await expect(dialog.getByLabel('Description (facultative)')).toHaveValue(
      'Une coupe légère et confortable.',
    );
    await dialog.getByLabel('Nom du produit').fill('Smartphone Samsung S24FE Ultra ' + 'ModeleSansEspace'.repeat(6));
    await noOverflow(page);
    mock.conflictNext();
    await dialog.getByRole('button', { name: 'Enregistrer les modifications' }).click();
    await expect(dialog.getByRole('alert')).toContainText('modifié');
    expect(state.products[0].name).toBe('Chemise en lin');
    await dialog.getByRole('button', { name: 'Actualiser les données du produit' }).click();
    await expect(dialog.getByLabel('Nom du produit')).toHaveValue('Chemise en lin');
    await dialog.getByLabel('Nom du produit').fill('Chemise en lin bleu');
    await dialog.getByLabel('Description (facultative)').fill('Nouvelle description');
    await dialog.getByRole('button', { name: 'Enregistrer les modifications' }).click();
    await expect(dialog).not.toBeVisible();
    await expect(page.locator('.catalog-product h3')).toContainText('Chemise en lin bleu');
    expect(state.products[0].name).toBe('Chemise en lin bleu');
    expect(state.products[0].description).toBe('Nouvelle description');
    expect(state.products[0].version).toBe(4);
    await noOverflow(page);

    // Canceling is not a write.
    await page.getByRole('button', { name: 'Modifier', exact: true }).click();
    await dialog.getByLabel('Nom du produit').fill('Ne doit pas être sauvegardé');
    await dialog.getByRole('button', { name: 'Annuler' }).click();
    expect(state.products[0].name).toBe('Chemise en lin bleu');
    await expect(dialog).not.toBeVisible();
  });
}

test('archiver au lieu de supprimer conserve la fiche et exige confirmation', async ({ page }) => {
  await session(page);
  const state = await mockApi(page);
  await mutableCatalog(page, state);
  await page.goto('/app/products');
  await page.getByRole('button', { name: 'Archiver' }).click();
  const dialog = page.getByRole('dialog', { name: 'Archiver un produit' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText(/peut être restauré/)).toBeVisible();
  await dialog.getByRole('button', { name: 'Annuler' }).click();
  expect(state.products[0].status).toBe('ACTIVE');
  await page.getByRole('button', { name: 'Archiver' }).click();
  await dialog.getByRole('button', { name: 'Confirmer l’archivage' }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.getByRole('button', { name: 'Archivés 1' })).toBeVisible();
  await page.getByRole('button', { name: 'Archivés 1' }).click();
  await expect(page.getByRole('heading', { name: 'Chemise en lin' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Modifier' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Archiver' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Restaurer' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Voir les images' })).toBeVisible();
  expect(state.products).toHaveLength(1);
  expect(state.products[0].status).toBe('ARCHIVED');
  await noOverflow(page);
});

test('archiver avec une version périmée oblige à actualiser avant confirmation', async ({ page }) => {
  await session(page);
  const state = await mockApi(page);
  await mutableCatalog(page, state);
  await page.goto('/app/products');
  await page.getByRole('button', { name: 'Archiver' }).click();
  state.products[0].version = 20;
  const dialog = page.getByRole('dialog', { name: 'Archiver un produit' });
  await dialog.getByRole('button', { name: 'Confirmer l’archivage' }).click();
  await expect(dialog.getByRole('alert')).toContainText('modifié');
  expect(state.products[0].status).toBe('ACTIVE');
  await dialog.getByRole('button', { name: 'Actualiser les informations' }).click();
  await expect(dialog.getByRole('alert')).toHaveCount(0);
  await dialog.getByRole('button', { name: 'Confirmer l’archivage' }).click();
  await expect(dialog).not.toBeVisible();
  expect(state.products[0].status).toBe('ARCHIVED');
});

test('ajouter une variante depuis Modifier, puis restaurer sans perdre l’identité', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 800 });
  await session(page);
  const state = await mockApi(page);
  const mock = await mutableCatalog(page, state);
  await page.goto('/app/products');
  await page.getByRole('button', { name: 'Modifier', exact: true }).click();
  const edit = page.getByRole('dialog', { name: 'Modifier un produit' });
  await expect(edit).toBeVisible();
  await edit.getByRole('button', { name: 'Nouvelle variante' }).click();
  await edit.getByRole('button', { name: 'Enregistrer la nouvelle variante' }).click();
  await expect(edit.getByRole('alert')).toContainText('Renseignez les options');
  await edit.getByRole('textbox', { name: 'Valeur' }).fill('Vert');
  mock.conflictNext();
  await edit.getByRole('button', { name: 'Enregistrer la nouvelle variante' }).click();
  await expect(edit.getByRole('alert')).toContainText('modifié');
  await edit.getByRole('button', { name: 'Actualiser le produit' }).click();
  await expect(edit.getByRole('alert')).toHaveCount(0);
  await edit.getByRole('button', { name: 'Enregistrer la nouvelle variante' }).click();
  await expect(edit.getByText('2 variante(s) existante(s)')).toBeVisible();
  expect(state.products[0].variants).toHaveLength(2);
  expect(state.products[0].variants?.[0].id).toBe('33333333-3333-4333-8333-333333333333');
  expect(state.products[0].variants?.[1].attributes).toEqual({ color: 'vert' });
  await edit.getByRole('button', { name: 'Annuler' }).click();
  await page.getByRole('button', { name: /Variantes/ }).click();
  await expect(page.locator('.variant-list li')).toHaveCount(2);
  await noOverflow(page);

  await page.getByRole('button', { name: 'Archiver' }).click();
  const archive = page.getByRole('dialog', { name: 'Archiver un produit' });
  await archive.getByRole('button', { name: 'Confirmer l’archivage' }).click();
  await expect(archive).not.toBeVisible();
  await page.getByRole('button', { name: 'Restaurer' }).click();
  const restore = page.getByRole('dialog', { name: 'Restaurer un produit' });
  await expect(restore.getByText(/anciennes offres encore/)).toBeVisible();
  await restore.getByRole('button', { name: 'Annuler' }).click();
  expect(state.products[0].status).toBe('ARCHIVED');
  await page.getByRole('button', { name: 'Restaurer' }).click();
  state.products[0].version = 20;
  await restore.getByRole('button', { name: 'Confirmer la restauration' }).click();
  await expect(restore.getByRole('alert')).toContainText('modifié');
  await restore.getByRole('button', { name: 'Actualiser les informations' }).click();
  await restore.getByRole('button', { name: 'Confirmer la restauration' }).click();
  await expect(restore).not.toBeVisible();
  expect(state.products[0].status).toBe('ACTIVE');
  expect(state.products[0].variants).toHaveLength(2);
  await expect(page.getByRole('button', { name: 'Modifier', exact: true })).toBeVisible();
  await noOverflow(page);
});
