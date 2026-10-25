import { expect, test, Page } from '@playwright/test';
import { mockApi, noOverflow, session, group, shopId } from './api-mock';
const secondId = '99999999-9999-4999-8999-999999999999';
async function setup(page: Page) {
  await session(page);
  const state = await mockApi(page, { multiple: true });
  state.groups = [
    { ...group, title: 'Sélection à préparer' },
    { ...group, id: secondId, title: 'Colis en route', status: 'IN_TRANSIT' },
  ];
  return state;
}
for (const width of [360, 390, 430, 768, 1024, 1280, 1440])
  for (const theme of ['light', 'dark']) {
    test(`cockpit ${width} ${theme} : priorité visible, clavier et texte 200%`, async ({
      page,
    }) => {
      await page.setViewportSize({ width, height: 960 });
      await page.addInitScript((t) => localStorage.setItem('groupia.theme', t), theme);
      await setup(page);
      await page.goto('/app/accueil');
      const priority = page.getByRole('link', { name: /Reprendre le brouillon/ });
      await expect(priority).toBeVisible();
      expect((await priority.boundingBox())!.y).toBeLessThan(600);
      await expect(page.getByText('Sélection à préparer', { exact: true })).toHaveCount(1);
      await expect(page.locator('g-sales-chart')).toHaveCount(0);
      await expect(page.locator('g-stat-card')).toHaveCount(0);
      await priority.focus();
      await expect(priority).toBeFocused();
      expect(await priority.evaluate((el) => getComputedStyle(el).outlineStyle)).not.toBe('none');
      expect((await priority.boundingBox())!.height).toBeGreaterThanOrEqual(44);
      await noOverflow(page);
      await page.evaluate(() => (document.documentElement.style.fontSize = '200%'));
      await noOverflow(page);
      await page.emulateMedia({ reducedMotion: 'reduce' });
      await page.keyboard.press('Enter');
      await expect(page).toHaveURL(new RegExp('/app/groupages/' + group.id + '$'));
    });
  }
test('Accueil mobile V3.4 : actions, repères, groupages, activité, commandes dans cet ordre', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await setup(page);
  await page.goto('/app/accueil');
  const sections = [
    '.priorities',
    '.operational-snapshot',
    '.campaigns',
    '.activity-preview',
    '.recent-panel',
  ];
  let previous = -1;
  for (const selector of sections) {
    const element = page.locator(selector);
    await expect(element).toBeVisible();
    const position = (await element.boundingBox())!.y;
    expect(position, selector + ' doit suivre la section précédente').toBeGreaterThan(previous);
    previous = position;
  }
  await expect(page.locator('.snapshot-card')).toHaveCount(2);
  await expect(page.locator('.mini-chart .mini-column')).toHaveCount(3);
  await expect(page.getByText('Parmi les 20 derniers groupages', { exact: true })).toBeVisible();
  await expect(page.locator('g-sales-chart')).toHaveCount(0);
  await noOverflow(page);
});

test('Accueil vide V3.4 : démarrage guidé sans indicateurs ni sections commerciales fictives', async ({
  page,
}) => {
  const state = await setup(page);
  state.groups = [];
  state.products = [];
  await page.goto('/app/accueil');
  await expect(page.getByRole('heading', { name: 'Votre premier groupage commence ici' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Préparer mon catalogue' })).toBeVisible();
  await expect(page.locator('.operational-snapshot, .activity-preview, .recent-panel')).toHaveCount(0);
  await expect(page.locator('.mini-chart')).toHaveCount(0);
  await noOverflow(page);
});

test('Accueil → Statistiques : un appel agrégé par page et aucun chargement opérationnel dans Statistiques', async ({
  page,
}) => {
  await setup(page);
  const calls: string[] = [];
  page.on('request', (req) => {
    if (
      /organizations\/.*\/(orders|grouping-campaigns|analytics\/business|products)\?/.test(
        req.url(),
      )
    )
      calls.push(req.url());
  });
  await page.goto('/app/accueil');
  await expect(page.locator('.preview-values')).toBeVisible();
  expect(calls).toHaveLength(3);
  expect(calls.some((url) => url.endsWith('months=3'))).toBe(true);
  expect(calls.some((url) => url.includes('orders?page=0&size=3'))).toBe(true);
  expect(calls.some((url) => url.includes('grouping-campaigns?page=0&size=20'))).toBe(true);
  await page.getByRole('link', { name: 'Voir les statistiques' }).click();
  await expect(page).toHaveURL(/app\/statistiques$/);
  await expect(page).toHaveTitle('Statistiques · Groupia');
  await expect(page.locator('g-sales-chart')).toBeVisible();
  expect(calls).toHaveLength(4);
  expect(calls.at(-1)).toContain('months=12');
  await expect(page.locator('g-recent-orders')).toHaveCount(0);
});
test('boutique sans groupage : onboarding réel, catalogue borné, pas de faux zéro après erreur', async ({
  page,
}) => {
  const state = await setup(page);
  state.groups = [];
  state.products = [];
  await page.goto('/app/accueil');
  await expect(page.getByRole('link', { name: /Préparer mon catalogue/ })).toBeVisible();
  expect(state.calls.filter((c) => c.path.endsWith('/products'))).toHaveLength(1);
  await expect(page.locator('.priorities')).toHaveCount(0);
  await page.route('**/grouping-campaigns?*', (r) => r.fulfill({ status: 503, json: {} }));
  await page.reload();
  await expect(page.getByRole('alert').first()).toContainText('groupages');
  await expect(page.getByText('Votre premier groupage commence ici', { exact: true })).toHaveCount(
    0,
  );
  await expect(page.locator('.preview-values')).toBeVisible();
});
test('groupage publié sans identifiant et distribution sans permission : aucune priorité inventée', async ({
  page,
}) => {
  const state = await setup(page);
  state.groups = [
    { ...group, status: 'OPEN', publicIdentifier: undefined },
    { ...group, id: secondId, status: 'ARRIVED' },
  ];
  await page.goto('/app/accueil');
  await expect(page.locator('.campaign-row')).toHaveCount(2);
  await expect(page.locator('.priority-row')).toHaveCount(0);
  await expect(page.getByText('Tout est à jour pour l’instant', { exact: true })).toHaveCount(0);
});
test('changement de boutique pendant chargement : les trois réponses anciennes restent invisibles', async ({
  page,
}) => {
  await setup(page);
  const releases: (() => void)[] = [];
  await page.route(`**/organizations/${shopId}/grouping-campaigns?*`, async (route) => {
    await new Promise<void>((resolve) => releases.push(resolve));
    await route.fulfill({
      json: { totalElements: 1, items: [{ ...group, title: 'ANCIENNE BOUTIQUE CONFIDENTIELLE' }] },
    });
  });
  await page.goto('/app/accueil');
  await expect(page.getByText('Chargement des groupages…')).toBeVisible();
  await page.getByRole('link', { name: 'Ouvrir ma boutique' }).click();
  await page.getByLabel('Changer de boutique').selectOption('77777777-7777-4777-8777-777777777777');
  await expect(page.locator('.shop-name')).toHaveText('Deuxième boutique');
  releases.forEach((resolve) => resolve());
  await expect(page.getByText('ANCIENNE BOUTIQUE CONFIDENTIELLE')).toHaveCount(0);
  await expect(page.getByRole('link', { name: /Reprendre le brouillon/ })).toBeVisible();
});
test('expiration sur les groupages : aucune priorité ni donnée après perte de session', async ({
  page,
}) => {
  await setup(page);
  await page.route('**/grouping-campaigns?*', async (route) => {
    await page.context().clearCookies();
    return route.fulfill({ status: 401, json: { code: 'AUTHENTICATION_REQUIRED' } });
  });
  await page.goto('/app/accueil');
  await expect(page).toHaveURL(/connexion/);
  await expect(page.locator('.priority-row, .preview-values, g-recent-orders')).toHaveCount(0);
});
test('déconnexion durant un chargement : aucune réponse tardive visible', async ({ page }) => {
  await setup(page);
  let release!: () => void;
  await page.route('**/grouping-campaigns?*', async (route) => {
    await new Promise<void>((resolve) => (release = resolve));
    await route.fulfill({ json: { totalElements: 1, items: [group] } });
  });
  await page.goto('/app/accueil');
  await expect(page.getByText('Chargement des groupages…')).toBeVisible();
  await page.getByRole('button', { name: 'Se déconnecter', exact: true }).click();
  await expect(page).toHaveURL(/connexion/);
  release();
  await expect(page.locator('.priority-row, .preview-values, g-recent-orders')).toHaveCount(0);
});

test('recherche desktop : Statistiques est une destination réelle', async ({ page }) => {
  await setup(page);
  await page.goto('/app/accueil');
  await page.getByRole('searchbox').fill('Statistiques');
  await page.locator('g-topbar').getByRole('link', { name: 'Statistiques', exact: true }).click();
  await expect(page).toHaveURL(/app\/statistiques$/);
  await expect(page.getByRole('heading', { name: 'Statistiques', exact: true })).toBeVisible();
});
test.describe('Accueil tactile', () => {
  test.use({ hasTouch: true, viewport: { width: 390, height: 844 } });
  test('ouvre les statistiques depuis le menu droit, sans cinquième onglet primaire', async ({
    page,
  }) => {
    await setup(page);
    await page.goto('/app/accueil');
    await expect(page.locator('g-mobile-bottom-nav .bar a')).toHaveCount(4);
    const menu = page.locator('g-topbar').getByRole('button', { name: 'Menu', exact: true });
    await menu.tap();
    const dialog = page.getByRole('dialog', { name: 'Menu Groupia' });
    await dialog.getByRole('link', { name: 'Statistiques', exact: true }).tap();
    await expect(page).toHaveURL(/app\/statistiques$/);
    await expect(dialog).not.toBeVisible();
    await expect(menu).toHaveAttribute('aria-expanded', 'false');
  });
});
