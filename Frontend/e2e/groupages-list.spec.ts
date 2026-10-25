import { expect, Page, test } from '@playwright/test';
import { group, groupId, mockApi, noOverflow, session } from './api-mock';

async function setup(page: Page) {
  await session(page);
  return mockApi(page);
}

for (const [width, height, theme] of [
  [360, 800, 'light'],
  [390, 844, 'dark'],
  [768, 1024, 'light'],
  [1280, 900, 'light'],
  [1440, 960, 'dark'],
] as const) {
  test(`Groupages V1 ${width}px ${theme} : CTA, statuts et responsive`, async ({ page }, info) => {
    await page.setViewportSize({ width, height });
    await page.addInitScript((value) => localStorage.setItem('groupia.theme', value), theme);
    await setup(page);
    await page.goto('/app/groupages');
    await expect(page.getByRole('heading', { name: 'Groupages', exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Créer un groupage', exact: true }))
      .toHaveAttribute('href', '/app/groupages/nouveau');
    await expect(page.locator('.groupage-card')).toHaveCount(1);
    const card = page.locator('.groupage-card');
    await expect(card.getByRole('heading', { name: group.title!, exact: true })).toBeVisible();
    await expect(card.getByRole('link', { name: 'Voir le groupage ' + group.title }))
      .toHaveAttribute('href', '/app/groupages/' + groupId);
    await expect(page.locator('g-sales-chart')).toHaveCount(0);
    await noOverflow(page);
    await page.screenshot({ path: info.outputPath(`groupages-v1-${width}-${theme}.png`), fullPage: true });
    await card.getByRole('link', { name: 'Voir le groupage ' + group.title }).click();
    await expect(page).toHaveURL(new RegExp('/app/groupages/' + groupId + '$'));
  });
}

test('Groupages V1 : le vrai état vide guide sans indicateurs fictifs', async ({ page }) => {
  const state = await setup(page);
  state.groups = [];
  await page.goto('/app/groupages');
  await expect(page.getByRole('heading', { name: 'Votre premier groupage vous attend' })).toBeVisible();
  await expect(page.locator('.groupage-card, .list-total, .pagination')).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Créer un groupage', exact: true })).toBeVisible();
  await noOverflow(page);
});

test('Groupages V1 : erreur réseau distincte d’une liste vide et réessai réel', async ({ page }) => {
  await setup(page);
  let failed = true;
  await page.route('**/grouping-campaigns?*', (route) => {
    if (failed) return route.fulfill({ status: 503, json: { code: 'SERVICE_UNAVAILABLE' } });
    return route.fulfill({ json: { page: 0, size: 20, totalPages: 1, totalElements: 1, items: [group] } });
  });
  await page.goto('/app/groupages');
  await expect(page.getByRole('heading', { name: 'Impossible de charger vos groupages' })).toBeVisible();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Votre premier groupage vous attend' })).toHaveCount(0);
  failed = false;
  await page.getByRole('button', { name: 'Réessayer' }).click();
  await expect(page.locator('.groupage-card')).toHaveCount(1);
});

test('Groupages V1 : pagination uniquement serveur, résultat de page vide non assimilé à boutique vide', async ({
  page,
}) => {
  await setup(page);
  const requested: number[] = [];
  await page.route('**/grouping-campaigns?*', (route) => {
    const url = new URL(route.request().url());
    const pageNumber = Number(url.searchParams.get('page') ?? 0);
    requested.push(pageNumber);
    return route.fulfill({
      json: {
        page: pageNumber,
        size: 20,
        totalPages: 2,
        totalElements: 21,
        items: pageNumber === 0 ? [group] : [],
      },
    });
  });
  await page.goto('/app/groupages');
  const pages = page.getByRole('navigation', { name: 'Pages des groupages' });
  await expect(pages).toContainText('Page 1 sur 2');
  await expect(pages.getByRole('button', { name: 'Précédent' })).toBeDisabled();
  await pages.getByRole('button', { name: 'Suivant' }).click();
  await expect(pages).toContainText('Page 2 sur 2');
  await expect(page.getByRole('heading', { name: 'Aucun groupage sur cette page' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Votre premier groupage vous attend' })).toHaveCount(0);
  await pages.getByRole('button', { name: 'Précédent' }).click();
  await expect(page.locator('.groupage-card')).toHaveCount(1);
  expect(requested).toEqual([0, 1, 0]);
  await noOverflow(page);
});
