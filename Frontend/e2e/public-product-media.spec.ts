import { expect, test } from '@playwright/test';
import { mockApi, noOverflow, publicId } from './api-mock';

for (const width of [390, 1280]) {
  test(`images produit publiques visibles ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await mockApi(page);

    await page.goto(`/public/groupages/${publicId}`);

    const mainImage = page.getByRole('img', { name: 'Chemise en lin' });
    await expect(mainImage).toBeVisible();
    await expect(mainImage).toHaveAttribute('src', /#primary$/);

    const secondPhoto = page.getByRole('button', {
      name: 'Afficher la photo 2 de Chemise en lin',
    });
    await expect(secondPhoto).toBeVisible();
    await secondPhoto.click();
    await expect(secondPhoto).toHaveAttribute('aria-pressed', 'true');
    await expect(mainImage).toHaveAttribute('src', /#secondary$/);

    await page.getByRole('button', { name: 'Ajouter à ma sélection' }).click();
    await page.getByRole('link', { name: 'Voir mon panier' }).click();

    await expect(page.locator('g-cart-lines img.cart-image')).toBeVisible();
    await expect(page.locator('g-cart-lines img.cart-image')).toHaveAttribute('src', /#primary$/);
    await noOverflow(page);
  });
}
