import { expect, test, Page } from '@playwright/test';
import { mockApi, session, productId, noOverflow } from './api-mock';
const png = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=',
  'base64',
);
const url = 'data:image/png;base64,' + png.toString('base64');
async function mediaApi(page: Page) {
  const state = {
    items: [] as {
      id: string;
      state: string;
      position: number;
      thumbnailUrl: string;
      displayUrl: string;
    }[],
    version: 0,
    failNext: false,
    keys: [] as string[],
  };
  await page.route('**/api/organizations/*/product-media?**', (route) =>
    route.fulfill({
      json: [
        {
          productId,
          enabled: true,
          imageCount: state.items.length,
          galleryVersion: state.version,
          primaryThumbnailUrl: state.items[0]?.thumbnailUrl,
          primaryDisplayUrl: state.items[0]?.displayUrl,
        },
      ],
    }),
  );
  await page.route('**/api/organizations/*/products/*/media**', async (route) => {
    const req = route.request(),
      path = new URL(req.url()).pathname;
    const gallery = () => ({
      productId,
      version: state.version,
      enabled: true,
      canManage: true,
      items: state.items,
      reservedCount: 0,
    });
    if (req.method() === 'POST') {
      expect(req.headers()['content-type']).toContain('multipart/form-data; boundary=');
      expect(req.headers()['authorization']).toBe('Bearer e2e-only-token');
      state.keys.push(req.headers()['idempotency-key']);
      if (state.failNext) {
        state.failNext = false;
        return route.fulfill({ status: 503, json: { code: 'MEDIA_PROCESSING_BUSY' } });
      }
      if (state.items.length >= 5)
        return route.fulfill({ status: 409, json: { code: 'MEDIA_LIMIT_REACHED' } });
      const id = crypto.randomUUID();
      const item = {
        id,
        state: 'READY',
        position: state.items.length,
        thumbnailUrl: url + '#' + id,
        displayUrl: url + '#' + id,
      };
      state.items.push(item);
      state.version++;
      return route.fulfill({ status: 201, json: item });
    }
    if (req.method() === 'PUT') {
      const body = req.postDataJSON();
      expect(body.version).toBe(state.version);
      state.items = body.mediaIds.map((id: string, position: number) => ({
        ...state.items.find((item) => item.id === id)!,
        position,
      }));
      state.version++;
      return route.fulfill({ json: gallery() });
    }
    if (req.method() === 'DELETE') {
      state.items = state.items
        .filter((item) => item.id !== path.split('/').pop())
        .map((item, position) => ({ ...item, position }));
      state.version++;
      return route.fulfill({ status: 204, body: '' });
    }
    return route.fulfill({ json: gallery() });
  });
  return state;
}
for (const width of [320, 360, 390, 430, 768, 1280]) {
  test(`galerie produit accessible ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await session(page);
    await mockApi(page);
    const state = await mediaApi(page);
    await page.goto('/app/products');
    await expect(page.getByLabel('Produit sans image')).toBeVisible();
    await page.getByRole('button', { name: 'Voir les images' }).click();
    await page.getByLabel('Ajouter des images').setInputFiles([
      { name: 'a.png', mimeType: 'image/png', buffer: png },
      { name: 'b.png', mimeType: 'image/png', buffer: png },
    ]);
    await page.getByRole('button', { name: 'Envoyer / réessayer' }).click();
    await expect(page.locator('g-product-gallery .gallery > li:has(.photo-choice)')).toHaveCount(2);
    await expect(page.locator('g-product-thumbnail.product-photo img')).toBeVisible();
    const gallerySection = page.locator('g-product-gallery section[aria-label="Images du produit"]');
    const second = state.items[1].id;
    const gallery = page.getByRole('dialog', { name: 'Gestion des images du produit' });
    await expect(gallery.locator('.photo-stage .hero-photo')).toBeVisible();
    await gallery.getByRole('button', { name: 'Sélectionner l’image 2' }).click();
    await expect(gallery.getByRole('button', { name: 'Sélectionner l’image 2' }))
      .toHaveAttribute('aria-pressed', 'true');
    await expect(gallery.locator('.photo-indicator')).toContainText('Photo 2');
    const makePrimary = gallery.getByRole('button', { name: 'Définir comme principale' });
    await makePrimary.focus();
    await page.keyboard.press('Enter');
    await expect.poll(() => state.items[0].id).toBe(second);
    await expect(gallerySection).toHaveAttribute('aria-busy', 'false');
    await expect(page.locator('g-product-gallery .gallery > li:has(.photo-choice)').first().locator('img')).toHaveAttribute(
      'src',
      new RegExp(second),
    );
    await expect(page.locator('g-product-gallery .gallery > li:has(.photo-choice)').first()).toContainText('Principale');
    await expect(gallery.locator('.photo-indicator')).toContainText('Photo principale');
    await expect(page.getByRole('button', { name: 'Descendre l’image 1', exact: true })).toBeEnabled();
    await page.getByRole('button', { name: 'Descendre l’image 1', exact: true }).click();
    await expect.poll(() => state.items[1].id).toBe(second);
    await expect(gallerySection).toHaveAttribute('aria-busy', 'false');
    await expect(page.locator('g-product-gallery .gallery > li:has(.photo-choice)').nth(1).locator('img')).toHaveAttribute(
      'src',
      new RegExp(second),
    );
    await expect(page.getByRole('button', { name: 'Monter l’image 2', exact: true })).toBeEnabled();
    await page.getByRole('button', { name: 'Monter l’image 2', exact: true }).click();
    await expect.poll(() => state.items[0].id).toBe(second);
    await expect(gallerySection).toHaveAttribute('aria-busy', 'false');
    await expect(page.locator('g-product-gallery .gallery > li:has(.photo-choice)').first().locator('img')).toHaveAttribute(
      'src',
      new RegExp(second),
    );
    await page.getByRole('button', { name: 'Supprimer l’image 1', exact: true }).click();
    await page.getByRole('button', { name: 'Confirmer la suppression' }).click();
    await expect(page.locator('g-product-gallery .gallery > li:has(.photo-choice)')).toHaveCount(1);
    expect(state.items[0].position).toBe(0);
    await noOverflow(page);
  });
}
test('erreur puis retry idempotent et limite cinq', async ({ page }) => {
  await session(page);
  await mockApi(page);
  const state = await mediaApi(page);
  state.failNext = true;
  await page.goto('/app/products');
  await page.getByRole('button', { name: 'Voir les images' }).click();
  await page
    .getByLabel('Ajouter des images')
    .setInputFiles({ name: 'a.png', mimeType: 'image/png', buffer: png });
  await page.getByRole('button', { name: 'Envoyer / réessayer' }).click();
  await expect(page.getByRole('alert')).toContainText('traitement');
  await page.getByRole('button', { name: 'Envoyer / réessayer' }).click();
  await expect(page.locator('g-product-gallery .gallery > li:has(.photo-choice)')).toHaveCount(1);
  await expect(page.locator('g-product-gallery section[aria-label="Images du produit"]')).toHaveAttribute('aria-busy', 'false');
  expect(state.keys[1]).toBe(state.keys[0]);
  await page
    .getByLabel('Ajouter des images')
    .setInputFiles(
      Array.from({ length: 4 }, (_, i) => ({
        name: `${i}.png`,
        mimeType: 'image/png',
        buffer: png,
      })),
    );
  await page.getByRole('button', { name: 'Envoyer / réessayer' }).click();
  await expect(page.locator('g-product-gallery .gallery > li:has(.photo-choice)')).toHaveCount(5);
  await expect(page.getByLabel('Ajouter des images')).toBeDisabled();
});

test('galerie : lecture seule et archive ne proposent pas d’actions non autorisées', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 800 });
  await session(page);
  await mockApi(page);
  await page.route('**/api/organizations/*/products/*/media**', route => {
    if (route.request().method() !== 'GET') throw new Error('Action non autorisée');
    return route.fulfill({ json: {
      productId, version: 1, enabled: true, canManage: false,
      reservedCount: 0, items: [
        { id: '11111111-2222-4333-8444-555555555555', position: 0,
          thumbnailUrl: url, displayUrl: url, state: 'READY' },
      ],
    } });
  });
  await page.goto('/app/products');
  await page.getByRole('button', { name: 'Voir les images' }).click();
  const modal = page.getByRole('dialog', { name: 'Gestion des images du produit' });
  await expect(modal.locator('.photo-stage .hero-photo')).toBeVisible();
  await expect(modal.getByRole('button', { name: 'Définir comme principale' })).toHaveCount(0);
  await expect(modal.getByRole('button', { name: /Supprimer l’image/ })).toHaveCount(0);
  await expect(modal.getByLabel('Ajouter des images')).toHaveCount(0);
  await noOverflow(page);
});
