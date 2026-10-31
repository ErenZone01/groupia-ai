import { test, expect } from '@playwright/test';
import { mockApi, session, noOverflow, groupId, publicId } from './api-mock';
for (const [width, theme] of [
  [360, 'dark'],
  [1440, 'light'],
] as const) {
  test(`parcours gérante complet ${width}px ${theme}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.addInitScript((t) => localStorage.setItem('groupia.theme', t), theme);
    const state = await mockApi(page, { empty: true });
    await page.goto('/connexion');
    await page.getByLabel('Numéro de téléphone').fill('+221771234567');
    await page.getByLabel('Mot de passe', { exact: true }).fill('une phrase suffisamment longue');
    await page.getByRole('button', { name: 'Se connecter', exact: true }).click();
    await expect(
      page.getByRole('heading', { name: 'Comment s’appelle votre boutique ?' }),
    ).toBeVisible();
    await page.getByLabel('Nom de la boutique').fill('La boutique de Mame');
    await page.getByRole('button', { name: 'Créer ma boutique' }).click();
    await expect(page.getByRole('heading', { name: 'Accueil', exact: true })).toBeVisible();
    await page.goto('/app/products');
    await page.getByRole('button', { name: 'Ajouter un produit', exact: true }).click();
    await page.getByLabel('Nom du produit').fill('Chemise en lin');
    await page.getByLabel('Description (facultative)').fill('Une coupe légère.');
    await page.getByRole('button', { name: 'Créer le produit', exact: true }).click();
    await page.getByRole('button', { name: 'Fermer', exact: true }).click();
    await page.getByRole('button', { name: /Variantes/ }).click();
    await expect(page.getByText('Standard', { exact: true })).toBeVisible();
    expect(state.products[0].variants?.[0]?.canonicalVariantKey).toBe('DEFAULT');
    await page.goto('/app/groupages');
    await page.getByRole('link', { name: 'Créer un groupage' }).click();
    await page.getByLabel('Titre', { exact: true }).fill('Les essentiels de la rentrée');
    await page.getByLabel('Description (facultative)').fill('Une sélection à partager ensemble.');
    await page.getByRole('button', { name: 'Continuer', exact: true }).click();
    await page.getByLabel('Tarification de la livraison').selectOption('FIXED');
    await page.getByLabel('Tarif de livraison (F CFA)').fill('2000');
    await page.getByRole('button', { name: 'Continuer', exact: true }).click();
    await page
      .getByLabel('Produit et variante')
      .selectOption({ label: 'Chemise en lin · Standard' });
    await page.getByLabel('Prix dans ce groupage (F CFA)').fill('5000');
    await page.getByRole('button', { name: 'Ajouter au groupage', exact: true }).click();
    await page.getByRole('button', { name: 'Continuer', exact: true }).click();
    await noOverflow(page);
    await page.screenshot({ path: `artifacts/wizard-${width}-${theme}.png`, fullPage: true });
    await page.getByRole('button', { name: 'Publier le groupage', exact: true }).click();
    await expect(
      page.getByRole('region', { name: 'Partager le groupage' }),
    ).toBeVisible();
    expect(state.groups[0].version).toBe(99);
    const publicLink = page.getByRole('link', { name: 'Voir comme une cliente' });
    await expect(publicLink).toHaveAttribute('href', '/public/groupages/' + publicId);
    await publicLink.click();
    await expect(page.getByRole('heading', { name: 'Les essentiels de la rentrée' })).toBeVisible();
    await page.getByRole('button', { name: 'Ajouter à ma sélection' }).click();
    await expect(page.getByRole('heading', { name: 'Ma sélection (1)' })).toBeVisible();
    expect(state.publicAuth.every((v) => v === undefined)).toBe(true);
    expect(state.calls.some((c) => /checkout|payments/.test(c.path))).toBe(false);
    await noOverflow(page);
  });
}
test('login refusé sans détails internes', async ({ page }) => {
  await mockApi(page, { loginFailure: true });
  await page.goto('/connexion');
  await page.getByLabel('Numéro de téléphone').fill('bad');
  await page.getByLabel('Mot de passe', { exact: true }).fill('bad');
  await page.getByRole('button', { name: 'Se connecter', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('incorrect');
  await expect(page.getByText('private internal data')).toHaveCount(0);
});
test('inscription puis connexion sans session fabriquée', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('/inscription');
  await page.getByLabel('Numéro de téléphone').fill('+221771234567');
  await page.getByLabel('Mot de passe', { exact: true }).fill('une phrase suffisamment longue');
  await page.getByLabel('Confirmer le mot de passe', { exact: true }).fill('une phrase suffisamment longue');
  await expect(page.getByRole('button', { name: 'Créer mon compte' })).toBeEnabled();
  await page.getByRole('button', { name: 'Créer mon compte' }).click();
  await expect(page).toHaveURL(/connexion/);
  expect(await page.evaluate(() => sessionStorage.getItem('groupia.session'))).toBeNull();
  expect(state.calls.some((c) => c.path === '/api/auth/register')).toBe(true);
});
test('déconnexion et guard', async ({ page }) => {
  await session(page);
  await mockApi(page);
  await page.goto('/app/boutique');
  await page.getByRole('button', { name: 'Se déconnecter' }).last().click();
  await expect(page).toHaveURL(/connexion/);
  expect(await page.evaluate(() => sessionStorage.getItem('groupia.session'))).toBeNull();
});
test('ancien JWT persistant ignoré sans cookie de session', async ({ page }) => {
  await page.addInitScript(() =>
    sessionStorage.setItem(
      'groupia.session',
      JSON.stringify({ accessToken: 'e2e-only-token', expiresAt: Date.now() + 2000 }),
    ),
  );
  await mockApi(page);
  await page.goto('/app/products');
  await expect(page).toHaveURL(/connexion/, { timeout: 10000 });
  expect(await page.evaluate(() => sessionStorage.getItem('groupia.session'))).toBeNull();
});
test('changement de boutique et validation de la sélection', async ({ page }) => {
  await session(page);
  await page.addInitScript(() => {
    if (!localStorage.getItem('groupia.boutique'))
      localStorage.setItem('groupia.boutique', 'foreign');
  });
  const state = await mockApi(page, { multiple: true });
  await page.goto('/app/boutique');
  await expect(page.getByRole('heading', { name: 'Boutique du Soleil' })).toBeVisible();
  await page.getByLabel('Changer de boutique').selectOption({ label: 'Deuxième boutique' });
  await expect(page).toHaveURL(/app\/accueil/);
  await page.goto('/app/products');
  await expect(page.getByRole('heading', { name: 'Chemise en lin' })).toBeVisible();
  expect(
    state.calls.some((c) => c.path.includes('/77777777-7777-4777-8777-777777777777/products')),
  ).toBe(true);
});
test('création produit avec options et SKU facultatif', async ({ page }) => {
  await session(page);
  const state = await mockApi(page, { empty: true });
  state.shops.push({ id: '11111111-1111-4111-8111-111111111111', name: 'Boutique' });
  await page.goto('/app/products');
  await page.getByRole('button', { name: 'Ajouter un produit', exact: true }).click();
  await page.getByLabel('Nom du produit').fill('Chaussures');
  await page.getByRole('button', { name: 'Ajouter une option', exact: true }).click();
  await page.getByRole('combobox', { name: 'Option', exact: true }).selectOption('shoeSize');
  await page.getByLabel('Valeur', { exact: true }).fill('42');
  await page.getByRole('button', { name: 'Créer le produit', exact: true }).click();
  await page.getByRole('button', { name: 'Fermer', exact: true }).click();
  await page.getByRole('button', { name: /Variantes/ }).click();
  await expect(page.getByText('Pointure : 42', { exact: true })).toBeVisible();
  expect(state.products[0].variants?.[0]?.sku).toBeUndefined();
});
test('conflit de version actualise sans republier automatiquement', async ({ page }) => {
  await session(page);
  const state = await mockApi(page, { conflict: true });
  await page.goto('/app/groupages/' + groupId);
  await page.getByRole('button', { name: 'Publier le groupage', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('actualisé');
  expect(state.calls.filter((c) => c.path.endsWith('/open'))).toHaveLength(1);
  await page.getByRole('button', { name: 'Publier le groupage', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Partager le groupage' })).toBeVisible();
});
test('page non commandable et variante désactivée', async ({ page }) => {
  await mockApi(page, { orderable: false, status: 'CLOSED' });
  await page.goto('/public/groupages/' + publicId);
  await expect(page.getByRole('button', { name: 'Ajouter à ma sélection' })).toBeDisabled();
  await expect(page.getByText('Les commandes de ce groupage sont fermées.')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Ma sélection (0)' })).toBeVisible();
});
for (const width of [360, 768, 1440])
  for (const theme of ['light', 'dark']) {
    test(`nouveaux écrans responsive ${width}px ${theme}`, async ({ page }) => {
      await page.setViewportSize({ width, height: 950 });
      await page.addInitScript((t) => localStorage.setItem('groupia.theme', t), theme);
      await session(page);
      await mockApi(page, { status: 'OPEN' });
      for (const [path, title, name] of [
        ['/app/products', 'Vos produits', 'catalogue'],
        ['/app/groupages', 'Groupages', 'groupages'],
        ['/app/groupages/' + groupId, 'Les essentiels de la rentrée', 'detail'],
        ['/public/groupages/' + publicId, 'Les essentiels de la rentrée', 'public'],
      ]) {
        await page.goto(path);
        await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
        await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
        await noOverflow(page);
        if (width !== 768)
          await page.screenshot({
            path: `artifacts/${name}-${width}-${theme}.png`,
            fullPage: true,
          });
      }
    });
  }

test('brouillon puis ajout depuis le détail, prix et fermeture versionnés', async ({ page }) => {
  await session(page);
  const state = await mockApi(page);
  state.groups[0].offers = [];
  await page.goto('/app/groupages/' + groupId);
  await page.getByRole('tab', { name: 'Offres', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Aucune offre' })).toBeVisible();
  await page.getByRole('button', { name: 'Ajouter une offre', exact: true }).click();
  await page.getByLabel('Produit et variante').selectOption({ label: 'Chemise en lin · Standard' });
  await page.getByLabel('Prix dans ce groupage (F CFA)').fill('5000');
  await page.getByRole('button', { name: 'Ajouter au groupage', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Chemise en lin', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Modifier le prix', exact: true }).click();
  await page.getByLabel('Prix dans ce groupage (F CFA)').fill('6000');
  await page.getByRole('button', { name: 'Enregistrer le prix', exact: true }).click();
  expect(state.groups[0].offers?.[0]?.unitPrice).toBe(6000);
  await page.getByRole('button', { name: 'Publier le groupage', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Partager le groupage' })).toBeVisible();
  await page.getByRole('tab', { name: 'Commandes', exact: true }).click();
  await page.getByRole('button', { name: 'Fermer les commandes', exact: true }).click();
  await page.getByRole('button', { name: 'Confirmer', exact: true }).click();
  await expect(page.getByText('Commandes fermées', { exact: true })).toBeVisible();
  expect(state.groups[0].status).toBe('CLOSED');
});

test('création sans produit autorise seulement un brouillon', async ({ page }) => {
  await session(page);
  const state = await mockApi(page);
  state.groups = [];
  await page.goto('/app/groupages/nouveau');
  await page.getByLabel('Titre', { exact: true }).fill('Mon brouillon');
  await page.getByRole('button', { name: 'Continuer', exact: true }).click();
  await page.getByLabel('Tarification de la livraison').selectOption('FIXED');
  await page.getByLabel('Tarif de livraison (F CFA)').fill('0');
  await page.getByRole('button', { name: 'Continuer', exact: true }).click();
  await page.getByRole('button', { name: 'Continuer', exact: true }).click();
  await expect(
    page.getByRole('button', { name: 'Publier le groupage', exact: true }),
  ).toBeDisabled();
  await page.getByRole('button', { name: 'Enregistrer comme brouillon', exact: true }).click();
  await expect(page).toHaveURL(new RegExp('/app/groupages/' + groupId));
  expect(state.calls.some((c) => c.path.endsWith('/open'))).toBe(false);
  expect(state.groups.at(-1)?.status).toBe('DRAFT');
});

test('production : connexion et client généré fonctionnels', async ({ page }) => {
  const state = await mockApi(page);
  await page.goto('http://127.0.0.1:4300/connexion');
  await page.getByLabel('Numéro de téléphone').fill('+221771234567');
  await page.getByLabel('Mot de passe', { exact: true }).fill('une phrase suffisamment longue');
  await page.getByRole('button', { name: 'Se connecter', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Accueil', exact: true })).toBeVisible();
  await page.goto('http://127.0.0.1:4300/app/products');
  await expect(page.getByRole('heading', { name: 'Chemise en lin', exact: true })).toBeVisible();
  expect(state.calls.some((c) => c.path.endsWith('/products'))).toBe(true);
});
