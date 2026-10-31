import { expect, test } from '@playwright/test';
import { mockApi, noOverflow, publicId, offerId } from './api-mock';

const publicUrl = '/public/groupages/' + publicId;

for (const [width, theme] of [[360, 'dark'], [390, 'light'], [768, 'dark'], [1440, 'light']] as const) {
  test(`vitrine V2 ${width}px ${theme}: données réelles, recherche, galerie et panier`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.addInitScript((value) => localStorage.setItem('groupia.theme', value), theme);
    const state = await mockApi(page, { status: 'OPEN' });
    await page.goto(publicUrl);

    await expect(page.getByRole('heading', { name: 'Les essentiels de la rentrée' })).toBeVisible();
    await expect(page.getByText('Transport maritime')).toBeVisible();
    await expect(page.locator('body')).not.toContainText('Chine → Dakar');
    await expect(page.locator('body')).not.toContainText('En stock');
    await expect(page.locator('.product-media-main img')).toHaveCSS('object-fit', 'contain');
    await expect(page.locator('.product-thumb')).toHaveCount(2);
    await page.getByRole('button', { name: 'Afficher la photo 2 de Chemise en lin' }).click();
    await expect(page.getByRole('button', { name: 'Afficher la photo 2 de Chemise en lin' }))
      .toHaveAttribute('aria-pressed', 'true');

    await page.getByRole('searchbox', { name: 'Rechercher un produit' }).fill('introuvable');
    await expect(page.getByRole('heading', { name: 'Aucun produit trouvé' })).toBeVisible();
    await page.getByRole('button', { name: 'Effacer la recherche' }).click();
    await expect(page.getByRole('button', { name: 'Ajouter à ma sélection' })).toBeVisible();
    await page.getByRole('button', { name: 'Ajouter à ma sélection' }).click();
    await expect(page.getByRole('heading', { name: 'Ma sélection (1)' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Voir mon panier' })).toHaveCount(1);
    expect(state.publicAuth.every((value) => value === undefined)).toBe(true);
    await noOverflow(page);

    await page.getByRole('link', { name: 'Voir mon panier' }).click();
    await expect(page.getByRole('heading', { name: 'Mon panier' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Résumé de ma commande' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Continuer vers mes coordonnées' })).toBeEnabled();
    await noOverflow(page);
  });
}

test('vitrine V2 : groupage fermé sans ajout possible', async ({ page }) => {
  await mockApi(page, { orderable: false, status: 'CLOSED' });
  await page.goto(publicUrl);
  await expect(page.getByText('Les commandes de ce groupage sont fermées.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Ajouter à ma sélection' })).toBeDisabled();
  await expect(page.getByRole('heading', { name: 'Ma sélection (0)' })).toBeVisible();
  await noOverflow(page);
});

test('panier V2 : changement de prix bloquant jusqu’à acceptation', async ({ page }) => {
  const state = await mockApi(page, { status: 'OPEN' });
  await page.goto(publicUrl);
  await page.getByRole('button', { name: 'Ajouter à ma sélection' }).click();
  state.groups[0].offers![0].unitPrice = 6500;
  state.groups[0].offers![0].priceRevision = 3;
  await page.getByRole('link', { name: 'Voir mon panier' }).click();
  await expect(page.getByText('Le prix a changé.', { exact: false })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Continuer vers mes coordonnées' }))
    .toHaveAttribute('aria-disabled', 'true');
  await page.getByRole('button', { name: 'Accepter le nouveau prix' }).click();
  await expect(page.getByRole('link', { name: 'Continuer vers mes coordonnées' }))
    .toBeEnabled();
  await noOverflow(page);
});

test('panier V2 : diminuer, augmenter, supprimer sans appel de paiement', async ({ page }) => {
  const state = await mockApi(page, { status: 'OPEN' });
  await page.goto(publicUrl);
  await page.getByRole('button', { name: 'Ajouter à ma sélection' }).click();
  await page.getByRole('link', { name: 'Voir mon panier' }).click();
  await page.getByRole('button', { name: 'Augmenter la quantité de Chemise en lin' }).click();
  await expect(page.getByText('Quantité : 2')).toBeVisible();
  await page.getByRole('button', { name: 'Diminuer la quantité de Chemise en lin' }).click();
  await expect(page.getByText('Quantité : 1')).toBeVisible();
  await page.getByRole('button', { name: 'Supprimer Chemise en lin' }).click();
  await expect(page.getByRole('heading', { name: 'Votre panier est vide' })).toBeVisible();
  expect(state.calls.some((call) => /checkout|payments/.test(call.path))).toBe(false);
});

test('vitrine V2 : frais selon la règle de collecte configurée, pas de trajet inventé', async ({ page }) => {
  const state = await mockApi(page, { status: 'OPEN' });
  state.groups[0].title = 'Fourniture de rentrée';
  state.groups[0].shippingTerms = {
    ...state.groups[0].shippingTerms,
    shippingCollectionMode: 'ESTIMATED_AT_CHECKOUT',
    transportMode: 'ROAD',
  };
  await page.goto(publicUrl);
  await expect(page.getByRole('heading', { name: 'Fourniture de rentrée' })).toBeVisible();
  await expect(page.getByText('Transport routier')).toBeVisible();
  await expect(page.getByText('Les frais de transport sont estimés lors de la commande.', { exact: false }))
    .toBeVisible();
  await expect(page.locator('body')).not.toContainText('Dakar');
  await noOverflow(page);
});
