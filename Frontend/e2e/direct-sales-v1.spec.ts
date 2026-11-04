import { expect, test } from '@playwright/test';
import { mockApi, noOverflow, publicProductImage, session, shopId, variantId } from './api-mock';

const publicIdentifier = '77777777-7777-4777-8777-777777777777';
const offerId = '88888888-8888-4888-8888-888888888888';

test('vente directe : vitrine anonyme, photo complète et aucun faux checkout', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockApi(page);
  let authHeader: string | undefined;
  let policyRequests = 0;
  await page.route('**/api/public/direct-stores/*/delivery-policy', (route) => {
    expect(route.request().headers()['authorization']).toBeUndefined();
    policyRequests++;
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ mode: 'SELECTED', allowedRegions: ['Dakar'], version: 1 }),
    });
  });
  // Match the storefront itself, not its /delivery-policy or /quote subroutes.
  await page.route(new RegExp('/api/public/direct-stores/' + publicIdentifier + '
    authHeader = route.request().headers()['authorization'];
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        publicIdentifier,
        displayName: 'Boutique Awa',
        currency: 'XOF',
        offers: [{
          id: offerId, variantId, priceXof: 8500, published: true, version: 1,
          productName: 'Sac en cuir', description: 'Un sac pratique',
          attributes: { color: 'Noir' }, saleUnit: 'UNIT', usable: true,
          imageUrl: publicProductImage,
        }],
      }),
    });
  });
  await page.route('**/api/public/direct-stores/*/quote', (route) => {
    const request = route.request();
    expect(request.headers()['authorization']).toBeUndefined();
    expect(request.postDataJSON()).toEqual({ lines: [{ offerId, quantity: 1 }] });
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        currency: 'XOF', displayName: 'Boutique Awa', goodsSubtotalXof: 8500,
        checkoutAvailable: false,
        lines: [{
          offerId, variantId, quantity: 1, unitPriceXof: 8500,
          lineTotalXof: 8500, priceRevision: 1, productName: 'Sac en cuir',
          attributes: { color: 'Noir' },
        }],
      }),
    });
  });
  await page.goto('/v/' + publicIdentifier);
  await expect(page.getByRole('heading', { name: 'Boutique Awa' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Sac en cuir' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Zones de livraison de la boutique' })).toContainText('Dakar');
  expect(policyRequests).toBe(1);
  await expect(page.getByText(/8\s?500/)).toBeVisible();
  await expect(page.getByText(/Panier et estimation disponibles/)).toBeVisible();
  await expect(page.getByRole('button', { name: /acheter|commander|payer/i })).toHaveCount(0);
  expect(authHeader).toBeUndefined();
  await page.getByRole('button', { name: 'Ajouter au panier' }).click();
  await expect(page.getByText('1 article(s) dans mon panier')).toBeVisible();
  await page.getByRole('link', { name: 'Voir mon panier' }).click();
  await expect(page.getByRole('heading', { name: 'Mon panier' })).toBeVisible();
  await expect(page.getByText('Sous-total des articles')).toBeVisible();
  await expect(page.getByText('Aucune commande ni paiement')).toBeVisible();
  await noOverflow(page);
});

test('vente directe : publication, prix fixe et lien stable dans le contexte de boutique', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 820 });
  await session(page);
  await mockApi(page);
  let store = {
    publicIdentifier: null as string | null,
    displayName: null as string | null,
    currency: 'XOF' as const,
    offers: [] as Array<Record<string, unknown>>,
  };
  await page.route('**/api/organizations/*/direct-store**', (route) => {
    const request = route.request();
    const url = new URL(request.url());
    expect(url.pathname).toContain(shopId);
    const method = request.method();
    if (method === 'PUT' && url.pathname.endsWith('/direct-store')) {
      store = { ...store, displayName: request.postDataJSON().displayName, publicIdentifier };
    } else if (method === 'POST' && url.pathname.endsWith('/offers')) {
      const body = request.postDataJSON();
      store.offers.unshift({
        id: offerId, variantId: body.variantId, priceXof: body.priceXof,
        published: false, version: 0, productName: 'Chemise en lin',
        attributes: {}, saleUnit: 'UNIT', usable: true, imageUrl: null,
      });
    } else if (method === 'PUT' && url.pathname.endsWith('/' + offerId)) {
      const input = request.postDataJSON();
      store.offers[0] = { ...store.offers[0], ...input, version: 1 };
    } else if (method !== 'GET') throw new Error('Unexpected request: ' + method);
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(store) });
  });
  await page.goto('/app/vente-directe');
  await expect(page.getByRole('heading', { name: 'Vente directe', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Enregistrer', exact: true }).first().click();
  await expect(page.getByLabel('Lien permanent de votre boutique')).toHaveValue(
    new URL('/v/' + publicIdentifier, page.url()).href,
  );
  await page.getByLabel('Produit et variante').selectOption(variantId);
  await page.getByLabel('Prix fixe en F CFA').fill('8500');
  await page.getByRole('button', { name: 'Ajouter en brouillon' }).click();
  await expect(page.getByText('Brouillon / masquée')).toBeVisible();
  await page.getByRole('button', { name: 'Publier', exact: true }).click();
  await expect(page.getByText('Publiée', { exact: true })).toBeVisible();
  await noOverflow(page);
});
), (route) => {
    authHeader = route.request().headers()['authorization'];
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        publicIdentifier,
        displayName: 'Boutique Awa',
        currency: 'XOF',
        offers: [{
          id: offerId, variantId, priceXof: 8500, published: true, version: 1,
          productName: 'Sac en cuir', description: 'Un sac pratique',
          attributes: { color: 'Noir' }, saleUnit: 'UNIT', usable: true,
          imageUrl: publicProductImage,
        }],
      }),
    });
  });
  await page.route('**/api/public/direct-stores/*/quote', (route) => {
    const request = route.request();
    expect(request.headers()['authorization']).toBeUndefined();
    expect(request.postDataJSON()).toEqual({ lines: [{ offerId, quantity: 1 }] });
    return route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({
        currency: 'XOF', displayName: 'Boutique Awa', goodsSubtotalXof: 8500,
        checkoutAvailable: false,
        lines: [{
          offerId, variantId, quantity: 1, unitPriceXof: 8500,
          lineTotalXof: 8500, priceRevision: 1, productName: 'Sac en cuir',
          attributes: { color: 'Noir' },
        }],
      }),
    });
  });
  await page.goto('/v/' + publicIdentifier);
  await expect(page.getByRole('heading', { name: 'Boutique Awa' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Sac en cuir' })).toBeVisible();
  await expect(page.getByText(/8\s?500/)).toBeVisible();
  await expect(page.getByText(/Panier et estimation disponibles/)).toBeVisible();
  await expect(page.getByRole('button', { name: /acheter|commander|payer/i })).toHaveCount(0);
  expect(authHeader).toBeUndefined();
  await page.getByRole('button', { name: 'Ajouter au panier' }).click();
  await expect(page.getByText('1 article(s) dans mon panier')).toBeVisible();
  await page.getByRole('link', { name: 'Voir mon panier' }).click();
  await expect(page.getByRole('heading', { name: 'Mon panier' })).toBeVisible();
  await expect(page.getByText('Sous-total des articles')).toBeVisible();
  await expect(page.getByText('Aucune commande ni paiement')).toBeVisible();
  await noOverflow(page);
});

test('vente directe : publication, prix fixe et lien stable dans le contexte de boutique', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 820 });
  await session(page);
  await mockApi(page);
  let store = {
    publicIdentifier: null as string | null,
    displayName: null as string | null,
    currency: 'XOF' as const,
    offers: [] as Array<Record<string, unknown>>,
  };
  await page.route('**/api/organizations/*/direct-store**', (route) => {
    const request = route.request();
    const url = new URL(request.url());
    expect(url.pathname).toContain(shopId);
    const method = request.method();
    if (method === 'PUT' && url.pathname.endsWith('/direct-store')) {
      store = { ...store, displayName: request.postDataJSON().displayName, publicIdentifier };
    } else if (method === 'POST' && url.pathname.endsWith('/offers')) {
      const body = request.postDataJSON();
      store.offers.unshift({
        id: offerId, variantId: body.variantId, priceXof: body.priceXof,
        published: false, version: 0, productName: 'Chemise en lin',
        attributes: {}, saleUnit: 'UNIT', usable: true, imageUrl: null,
      });
    } else if (method === 'PUT' && url.pathname.endsWith('/' + offerId)) {
      const input = request.postDataJSON();
      store.offers[0] = { ...store.offers[0], ...input, version: 1 };
    } else if (method !== 'GET') throw new Error('Unexpected request: ' + method);
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(store) });
  });
  await page.goto('/app/vente-directe');
  await expect(page.getByRole('heading', { name: 'Vente directe', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Enregistrer', exact: true }).first().click();
  await expect(page.getByLabel('Lien permanent de votre boutique')).toHaveValue(
    new URL('/v/' + publicIdentifier, page.url()).href,
  );
  await page.getByLabel('Produit et variante').selectOption(variantId);
  await page.getByLabel('Prix fixe en F CFA').fill('8500');
  await page.getByRole('button', { name: 'Ajouter en brouillon' }).click();
  await expect(page.getByText('Brouillon / masquée')).toBeVisible();
  await page.getByRole('button', { name: 'Publier', exact: true }).click();
  await expect(page.getByText('Publiée', { exact: true })).toBeVisible();
  await noOverflow(page);
});
